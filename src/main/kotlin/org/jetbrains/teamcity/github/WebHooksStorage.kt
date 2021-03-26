/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.teamcity.github

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.serverSide.ServerPaths
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.serverSide.impl.FileWatcherFactory
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.FileUtil
import jetbrains.buildServer.util.cache.CacheProvider
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.controllers.bad
import org.jetbrains.teamcity.github.json.HookInfoTypeAdapter
import org.jetbrains.teamcity.github.json.SimpleDateTypeAdapter
import java.io.File
import java.lang.reflect.Type
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Webhooks info storage
 * Backend: 'commit-hooks/webhooks.json' file under pluginData folder
 *
 * Data loaded from disk only on server start
 * Data persisted onto disk only on server stop
 */
class WebHooksStorage(cacheProvider: CacheProvider,
                      fileWatcherFactory: FileWatcherFactory,
                      private val myServerPaths: ServerPaths,
                      private val myServerEventDispatcher: EventDispatcher<BuildServerListener>) {
    companion object {
        private val LOG: Logger = Util.getLogger(WebHooksStorage::class.java)

        private const val VERSION: Int = 1

        val hooksListType: Type = object : TypeToken<List<WebHookInfo>>() {}.type

        val gson: Gson = GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Date::class.java, SimpleDateTypeAdapter)
                .registerTypeAdapter(WebHookInfo::class.java, HookInfoTypeAdapter)
                .create()!!

        internal fun getJsonObjectFromData(data: List<WebHookInfo>): JsonObject {
            val list = data.toHashSet().toMutableList()
            list.sortWith(Comparator { a, b -> a.key.toString().compareTo(b.key.toString()) })

            val obj = JsonObject()
            obj.addProperty("version", VERSION)
            obj.add("hooks", gson.toJsonTree(list, hooksListType))
            return obj
        }

        internal fun getDataFromJsonObject(obj: JsonObject): Map<MapKey, List<WebHookInfo>>? {
            val version = obj.getAsJsonPrimitive("version").asInt
            if (version != VERSION) {
                LOG.warn("Stored data have outdated version $version")
                return null
            }
            val array = obj.getAsJsonArray("hooks")
            val hooks = gson.fromJson<List<WebHookInfo>>(array, hooksListType)

            return hooks.groupBy { it.key.toMapKey() }
        }
    }

    // URLs:
    // https://teamcity-github-enterprise.labs.intellij.net/api/v3/repos/Vlad/test/hooks/88
    // https://api.github.com/repos/VladRassokhin/intellij-hcl/hooks/9124004

    // Keys:
    // teamcity-github-enterprise.labs.intellij.net/Vlad/test/88
    // github.com/VladRassokhin/intellij-hcl/9124004

    data class Key(val server: String, val owner: String, val name: String, val id: Long) {
        companion object {
            fun fromString(serialized: String): Key {
                val split = serialized.split('/')
                if (split.size < 4) throw IllegalArgumentException("Not an proper key: \"$serialized\"")
                val id = split[split.lastIndex].toLong()
                val name = split[split.lastIndex - 1]
                val owner = split[split.lastIndex - 2]
                val server = split.dropLast(3).joinToString("/")
                return Key(server, owner, name, id)
            }

            fun fromHookUrl(hookUrl: String): Key {
                val split = ArrayDeque(hookUrl.split('/'))
                assert(split.size >= 8)
                val id = split.pollLast().toLong()
                split.pollLast() // "hooks"
                val name = split.pollLast()
                val owner = split.pollLast()
                split.pollLast() // "repos"
                val serverOfV3 = split.pollLast()
                val server = if (serverOfV3 == "api.github.com")  "github.com" else {
                    split.pollLast()
                    split.pollLast()
                }
                return Key(server, owner, name, id)
            }
        }

        override fun toString(): String {
            return "$server/$owner/$name/$id"
        }

        fun toMapKey(): MapKey {
            return MapKey(server.trimEnd('/'), owner, name)
        }
    }

    data class MapKey internal constructor(val server: String, val owner: String, val name: String) {

        private val hashCode = Objects.hash(server.toLowerCase(), owner.toLowerCase(), name.toLowerCase())

        constructor(server: String, repo: RepositoryId) : this(server.trimEnd('/'), repo.owner, repo.name)

        override fun toString(): String {
            return "$server/$owner/$name"
        }

        fun toInfo(): GitHubRepositoryInfo = GitHubRepositoryInfo(server, owner, name)

        override fun equals(other: Any?): Boolean  {
            return if (other is MapKey) {
                server.equals(other.server, true) && owner.equals(other.owner, true) && name.equals(other.name, true)
            } else {
                false
            }
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }

    private val myData = HashMap<MapKey, MutableList<WebHookInfo>>()
    private val myDataLock = ReentrantReadWriteLock()

    private val myFileWatcher = fileWatcherFactory.createSingleFilesWatcher(getStorageFile(),
                                                                            TeamCityProperties.getInteger("teamcity.commitHooks.webHookStorage.watchInterval", 5000))

    private val myServerListener = object : BuildServerAdapter() {
        override fun serverStartup() {
            load()

            myFileWatcher.registerListener {
                load()
            }
            myFileWatcher.start()

            // Drop old caches from pre-release versions of plugin
            try {
                cacheProvider.destroyCache("WebHooksCache")
                cacheProvider.destroyCache("WebHooksAuthCache")
            } catch(e: Exception) {
            }
        }

        override fun serverShutdown() {
            persist()
            myFileWatcher.stop()
        }
    }

    fun init() {
        myServerEventDispatcher.addListener(myServerListener)
    }

    fun destroy() {
        myServerEventDispatcher.removeListener(myServerListener)
    }

    /**
     * Adds hook if it not existed previously
     */
    fun getOrAdd(created: RepositoryHook): WebHookInfo {
        val key = Key.fromHookUrl(created.url)
        val mapKey = key.toMapKey()

        val hooks = getHooks(mapKey)
        val hook = hooks.firstOrNull { it.isSame(created) }
        if (hook != null) {
            LOG.info("Already exist $hook")
            return hook
        }

        myDataLock.write {
            @Suppress("NAME_SHADOWING")
            var hooks = myData[mapKey]
            @Suppress("NAME_SHADOWING")
            val hook = hooks?.firstOrNull { it.isSame(created) }
            if (hook != null) return hook

            val toAdd = WebHookInfo(url = created.url, callbackUrl = created.callbackUrl!!, key = key, status = created.getStatus())

            if (hooks == null || hooks.isEmpty()) {
                hooks = mutableListOf(toAdd)
                myData[mapKey] = hooks
            } else {
                hooks.add(toAdd)
                myData[mapKey] = hooks
            }
            LOG.info("Added $toAdd")
            return toAdd
        }
    }

    fun delete(hookInfo: WebHookInfo) {
        LOG.info("Removing $hookInfo")
        myDataLock.write {
            myData[hookInfo.key.toMapKey()]?.remove(hookInfo)
        }
    }

    fun delete(info: GitHubRepositoryInfo, deleteFilter: (WebHookInfo) -> Boolean) {
        if (!getHooks(info).any { deleteFilter(it) }) return

        val key = MapKey(info.server, info.getRepositoryId())
        myDataLock.write {
            val hooks = myData[key]?.toMutableList() ?: return
            val filtered = hooks.filter { !deleteFilter(it) }.toMutableList()
            if (filtered.isEmpty()) {
                myData.remove(key)
            } else {
                myData.put(key, filtered)
            }
        }
    }

    fun update(server: String, repo: RepositoryId, update: (WebHookInfo) -> Unit): Boolean {
        val key = MapKey(server, repo)
        val hooks = myDataLock.read {
            myData[key]?.toMutableList() ?: return false
        }
        for (hook in hooks) {
            update(hook)
        }
        return true
    }

    fun getHooks(info: GitHubRepositoryInfo): List<WebHookInfo> {
        return getHooks(info.server, info.getRepositoryId())
    }

    fun getHooks(server: String, repo: RepositoryId): List<WebHookInfo> {
        return getHooks(MapKey(server, repo))
    }

    private fun getHooks(key: MapKey): List<WebHookInfo> {
        return myDataLock.read {
            myData[key]?.toList()
        } ?: emptyList()
    }

    fun isHasIncorrectHooks(): Boolean {
        myDataLock.read {
            for (value in myData.values) {
                if (value.any { it.status.bad }) return true
            }
        }
        return false
    }

    fun getIncorrectHooks(): List<Pair<GitHubRepositoryInfo, WebHookInfo>> {
        val result = ArrayList<Pair<GitHubRepositoryInfo, WebHookInfo>>()
        myDataLock.read {
            for ((key, hooks) in myData) {
                val bad = hooks.filter { it.status.bad }
                if (bad.isNotEmpty()) {
                    val info = key.toInfo()
                    bad.map { info to it }.toCollection(result)
                }
            }
        }
        return result
    }

    fun getAll(): List<Pair<GitHubRepositoryInfo, WebHookInfo>> {
        val result = ArrayList<Pair<GitHubRepositoryInfo, WebHookInfo>>()
        myDataLock.read {
            for ((key, hooks) in myData) {
                val info = key.toInfo()
                hooks.map { info to it }.toCollection(result)
            }
        }
        return result
    }

    private fun getStorageFile(): File {
        return File(myServerPaths.pluginDataDirectory, "commit-hooks/webhooks.json")
    }

    private fun persist() {
        myFileWatcher.runActionWithDisabledObserver {
            persistImpl()
        }
    }

    @Synchronized
    private fun persistImpl() {
        val obj = myDataLock.read {
            getJsonObjectFromData(myData.values.flatten())
        }

        val file = getStorageFile()

        try {
            FileUtil.createParentDirs(file)
            val writer = file.writer(Charsets.UTF_8).buffered()
            writer.use {
                gson.toJson(obj, it)
            }
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Cannot write auth-data to file '${file.absolutePath}'", e)
        }
    }


    @Synchronized private fun load(): Boolean {
        val file = getStorageFile()

        val obj: JsonObject?
        try {
            FileUtil.createParentDirs(file)
            if (!file.isFile) return false
            obj = file.reader(Charsets.UTF_8).buffered().use {
                gson.fromJson<JsonObject>(it, JsonObject::class.java)
            }
        } catch(e: Exception) {
            LOG.warnAndDebugDetails("Cannot read auth-data from file '${file.absolutePath}'", e)
            return false
        }

        if (obj == null) {
            LOG.warn("Stored object is null")
            return false
        }

        val data = getDataFromJsonObject(obj) ?: return false

        myDataLock.write {
            myData.clear()
            for ((key, value) in data) {
                myData[key] = value.toMutableList()
            }
        }
        return true
    }
}