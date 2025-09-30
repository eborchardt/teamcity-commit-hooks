package org.jetbrains.teamcity.github

import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.impl.BaseServerTestCase
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.users.UserModelEx
import jetbrains.buildServer.util.EventDispatcher
import org.assertj.core.api.BDDAssertions.then
import org.eclipse.egit.github.core.RepositoryHook
import org.eclipse.egit.github.core.RepositoryId
import org.jetbrains.teamcity.github.controllers.GitHubWebHookListener
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

class WebhookPeriodicalCheckerOrphanRemovalTest: BaseServerTestCase() {

    private lateinit var storage: WebHooksStorage
    private lateinit var authStorage: AuthDataStorage

    private lateinit var projectManager: ProjectManager
    private lateinit var oAuthConnectionsManager: OAuthConnectionsManager
    private lateinit var userModel: UserModelEx
    private lateinit var oAuthTokensStorage: OAuthTokensStorage

    @BeforeMethod
    override fun setUp() {
        super.setUp()
        // Real storages backed by temp data dir from fixture
        storage = WebHooksStorage(
            myFixture.cacheProvider,
            myFixture.fileWatcherFactory,
            myFixture.serverPaths,
            myFixture.eventDispatcher,
            myFixture.executorServices
        )
        authStorage = AuthDataStorage(
            myFixture.executorServices,
            myFixture.fileWatcherFactory,
            myFixture.serverPaths,
            myFixture.eventDispatcher
        )

        // Common services
        projectManager = myFixture.projectManager
        oAuthConnectionsManager = myFixture.getSingletonService(OAuthConnectionsManager::class.java)
        userModel = myFixture.getSingletonService(UserModelEx::class.java)
        oAuthTokensStorage = myFixture.getSingletonService(OAuthTokensStorage::class.java)

        // Ensure clean state
        System.clearProperty("teamcity.githubWebhooks.removeOrphanWebhooks")
    }

    @AfterMethod
    override fun tearDown() {
        System.clearProperty("teamcity.githubWebhooks.removeOrphanWebhooks")
        super.tearDown()
    }

    @Test
    fun `orphan webhook is removed with auth when flag enabled`() {
        // enable feature flag
        System.setProperty("teamcity.githubWebhooks.removeOrphanWebhooks", "true")

        // Prepare a webhook in storage with a callback URL containing a pubKey
        val pub = "pub-key-123"
        val repoHook = repoHook(1001, callbackUrlWith(pub))
        val hookInfo = storage.getOrAdd(repoHook)
        val info: GitHubRepositoryInfo = hookInfo.key.toInfo()

        // Add matching auth-data referencing this repository and pubKey
        val connectionInfo = AuthDataStorage.ConnectionInfo(id = "conn-id", projectExternalId = myFixture.rootProject.externalId)
        val auth = AuthDataStorage.AuthData(userId = 1L, public = pub, secret = "secret", repository = info, connection = connectionInfo)
        authStorage.store(auth)

        // Sanity
        then(storage.getAll().map { it.second }).hasSize(1)
        then(authStorage.find(pub)).isNotNull()

        // Build checker
        val webHooksManager = WebHooksManager(
            myFixture.webLinks,
            EventDispatcher.create(jetbrains.buildServer.vcs.RepositoryStateListener::class.java),
            authStorage,
            storage
        )
        val tokensHelper = TokensHelper(myFixture.getSingletonService(jetbrains.buildServer.serverSide.connections.ProjectConnectionsManager::class.java), oAuthTokensStorage)

        val checker = WebhookPeriodicalChecker(
            projectManager,
            oAuthConnectionsManager,
            authStorage,
            storage,
            userModel,
            webHooksManager,
            myFixture.executorServices,
            oAuthTokensStorage,
            tokensHelper
        )

        // No VCS roots exist in the test fixture by default, so the webhook is orphaned
        checker.doCheck()

        // Assert: hook removed from storage and auth removed
        then(storage.getAll()).isEmpty()
        then(authStorage.find(pub)).isNull()
    }

    @Test
    fun `orphan webhook is not removed when flag disabled`() {
        // explicitly disable
        System.setProperty("teamcity.githubWebhooks.removeOrphanWebhooks", "false")

        val pub = "pub-key-456"
        val repoHook = repoHook(2002, callbackUrlWith(pub))
        val hookInfo = storage.getOrAdd(repoHook)
        val info: GitHubRepositoryInfo = hookInfo.key.toInfo()

        val connectionInfo = AuthDataStorage.ConnectionInfo(id = "conn-id", projectExternalId = myFixture.rootProject.externalId)
        val auth = AuthDataStorage.AuthData(userId = 1L, public = pub, secret = "secret", repository = info, connection = connectionInfo)
        authStorage.store(auth)

        val webHooksManager = WebHooksManager(
            myFixture.webLinks,
            EventDispatcher.create(jetbrains.buildServer.vcs.RepositoryStateListener::class.java),
            authStorage,
            storage
        )
        val tokensHelper = TokensHelper(myFixture.getSingletonService(jetbrains.buildServer.serverSide.connections.ProjectConnectionsManager::class.java), oAuthTokensStorage)

        val checker = WebhookPeriodicalChecker(
            projectManager,
            oAuthConnectionsManager,
            authStorage,
            storage,
            userModel,
            webHooksManager,
            myFixture.executorServices,
            oAuthTokensStorage,
            tokensHelper
        )

        checker.doCheck()

        // Assert: hook remains and auth remains
        then(storage.getAll().map { it.second }).hasSize(1)
        then(authStorage.find(pub)).isNotNull()
    }

    private fun repoHook(hookId: Long, callbackUrl: String): RepositoryHook {
        val hook = RepositoryHook()
        hook.id = hookId
        // hook.url determines GitHubRepositoryInfo via HookKey
        hook.url = "https://api.github.com/repos/test-owner/test-repo/hooks/$hookId"
        // TeamCity code reads callbackUrl via RepositoryHookEx
        hook.config = HashMap<String, String>().apply {
            put("url", callbackUrl)
            put(GitHubConstants.HOOK_CONTENT_TYPE, "json")
        }
        hook.isActive = true
        return hook
    }

    private fun callbackUrlWith(pubKey: String): String {
        // Any base URL is fine, important part is the path suffix
        return "http://localhost${GitHubWebHookListener.PATH}/$pubKey"
    }
}
