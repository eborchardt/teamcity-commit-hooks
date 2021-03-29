package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.impl.BaseServerTestCase
import jetbrains.buildServer.util.cache.CacheProvider
import org.eclipse.egit.github.core.RepositoryHook
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import org.assertj.core.api.BDDAssertions.then
import org.jetbrains.teamcity.github.controllers.Status

class WebHooksStorageIntegrationTest: BaseServerTestCase() {

    private var hookStorage: WebHooksStorage? = null

    @BeforeMethod
    override fun setUp() {
        super.setUp()
        hookStorage = WebHooksStorage(myFixture.getSingletonService<CacheProvider>(CacheProvider::class.java), myFixture.fileWatcherFactory,
                                      myFixture.serverPaths, myFixture.eventDispatcher, myFixture.executorServices)
    }

    @Test
    fun `test add hook`() {
        val hook = repoHook(123,  "http://fake.callback.url")
        val info = hookStorage?.getOrAdd(hook)
        then(info).isNotNull()
        then(info?.status).isEqualTo(Status.WAITING_FOR_SERVER_RESPONSE)
        val all = hookStorage?.getAll()
        then(all?.map { it.second }).isEqualTo(listOf(info))
    }

    @Test
    fun `test 2 hooks`() {
        val hook = repoHook(123,  "http://fake.callback.url")
        hookStorage?.getOrAdd(hook)
        then(hookStorage?.getAll()).hasSize(1)
        val hook2 = repoHook(345,  "http://fake.callback.url")
        hookStorage?.getOrAdd(hook2)
        then(hookStorage?.getAll()).hasSize(2)
    }

    @Test
    fun `test 2 identical hooks`() {
        val hook = repoHook(123,  "http://fake.callback.url")
        hookStorage?.getOrAdd(hook)
        then(hookStorage?.getAll()).hasSize(1)
        val hook2 = repoHook(123,  "http://fake.callback.url")
        hookStorage?.getOrAdd(hook2)
        then(hookStorage?.getAll()).hasSize(1)
    }

    @Test
    fun `test hook saved to disk`() {
        val hook = repoHook(123,  "http://fake.callback.url")
        val info = hookStorage?.getOrAdd(hook)
        then(info).isNotNull()
        then(info?.status).isEqualTo(Status.WAITING_FOR_SERVER_RESPONSE)
        val f = hookStorage?.getStorageFile()
        waitFor({ f?.exists()!! }, 10000L)
        then(f?.readText()).contains("hooks/123")
    }

    private fun repoHook(hookId: Long, callbackUrl: String): RepositoryHook {
        val hook = RepositoryHook()
        hook.id = hookId
        hook.url = "https://api.github.com/repos/myowner/myrepo/hooks/${hookId}"
        hook.config = HashMap<String, String>()
        hook.config["url"] = callbackUrl
        hook.isActive = true
        return hook
    }
}