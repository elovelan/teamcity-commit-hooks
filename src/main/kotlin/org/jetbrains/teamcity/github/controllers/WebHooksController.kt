package org.jetbrains.teamcity.github.controllers

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.serverSide.oauth.OAuthTokensStorage
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientFactory
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants
import jetbrains.buildServer.util.PropertiesUtil
import jetbrains.buildServer.web.openapi.PluginDescriptor
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import jetbrains.buildServer.web.util.WebUtil
import org.intellij.lang.annotations.MagicConstant
import org.jetbrains.teamcity.github.TokensHelper
import org.jetbrains.teamcity.github.Util
import org.jetbrains.teamcity.github.VcsRootGitHubInfo
import org.jetbrains.teamcity.github.WebHooksManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.servlet.ModelAndView
import java.io.IOException
import java.io.OutputStreamWriter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

public class WebHooksController(private val descriptor: PluginDescriptor, server: SBuildServer) : BaseController(server) {

    @Autowired
    lateinit var myWebControllerManager: WebControllerManager

    @Autowired
    lateinit var myOAuthConnectionsManager: OAuthConnectionsManager

    @Autowired
    lateinit var myOAuthTokensStorage: OAuthTokensStorage

    @Autowired
    lateinit var myWebHooksManager: WebHooksManager

    @Autowired
    lateinit var myTokensHelper: TokensHelper

    @Autowired
    lateinit var myProjectManager: ProjectManager

    private val myResultJspPath = descriptor.getPluginResourcesPath("hook-created.jsp")


    public fun register(): Unit {
        myWebControllerManager.registerController(PATH, this)
    }

    companion object {
        public val PATH = "/oauth/github/webhooks.html"

        private val LOG = Logger.getInstance(WebHooksController::class.java.name)

        class RequestException private constructor(val element: JsonElement) : Exception() {
            constructor(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int) : this(error_json(message, code)) {
            }
        }
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        var action = request.getParameter("action")
        val popup = PropertiesUtil.getBoolean(request.getParameter("popup"))
        val element: JsonElement
        var direct: Boolean = false
        if (!popup) {
            direct = true
        }
        try {
            if ("add" == action || action == null) {
                element = doHandleAddAction(request, response, action, popup)
            } else if ("check" == action) {
                element = doHandleCheckAction(request, response, action, popup)
            } else if ("delete" == action) {
                element = doHandleDeleteAction(request, response, action, popup)
            } else if ("continue" == action) {
                action = request.getParameter("original_action") ?: "add"
                element = doHandleAddAction(request, response, action, popup)
            } else if ("check-all" == action) {
                element = doHandleCheckAllAction(request, response, action, popup)
            } else {
                LOG.warn("Unknown action '$action'")
                return null
            }
        } catch(e: RequestException) {
            element = e.element
        }
        if (element is JsonObject) {
            element.addProperty("action", action)
        }
        if (direct) {
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val writer = JsonWriter(OutputStreamWriter(response.outputStream))
            Gson().toJson(element, writer)
            writer.flush()
            return null
        } else if (popup) {
            if (element is JsonObject) {
                val url = element.getAsJsonPrimitive("redirect")?.asString
                if (url != null) {
                    return redirectTo(url, response)
                }
            }
        }
        return callbackPage(element)
    }

    private fun callbackPage(element: JsonElement): ModelAndView {
        val mav = ModelAndView(myResultJspPath)
        mav.model.put("json", Gson().toJson(element))
        return mav
    }

    @Throws(RequestException::class)
    private fun doHandleAddAction(request: HttpServletRequest, response: HttpServletResponse, action: String, popup: Boolean): JsonElement {
        val user = SessionUser.getUser(request) ?: return error_json("Not authenticated", HttpServletResponse.SC_UNAUTHORIZED)

        val inType = request.getParameter("type")?.toLowerCase() ?: return error_json("Required parameter 'type' is missing", HttpServletResponse.SC_BAD_REQUEST)
        val inId = request.getParameter("id") ?: return error_json("Required parameter 'id' is missing", HttpServletResponse.SC_BAD_REQUEST)
        val inProjectId = request.getParameter("projectId")

        var connection: OAuthConnectionDescriptor? = getConnection(request, inProjectId)

        val info: VcsRootGitHubInfo
        val project: SProject

        if ("repository" == inType) {
            val pair = getRepositoryInfo(inProjectId, inId)
            project = pair.first
            info = pair.second
            LOG.info("Trying to create web hook for repository with id '$inId', repository info is '$info', user is '${user.describe(false)}', connection is ${connection?.id ?: "not specified in request"}")
        } else {
            return error_json("Parameter 'type' have unknown value", HttpServletResponse.SC_BAD_REQUEST)
        }

        if (connection != null && !Util.isConnectionToServer(connection, info.server)) {
            return error_json("OAuth connection '${connection.connectionDisplayName}' server doesn't match repository server '${info.server}'", HttpServletResponse.SC_BAD_REQUEST)
        }

        val connections: List<OAuthConnectionDescriptor>
        if (connection != null) {
            connections = listOf(connection)
        } else {
            connections = Util.findConnections(myOAuthConnectionsManager, project, info.server)
            if (connections.isEmpty()) {
                return error_json("No OAuth connection found for GitHub server '${info.server}' in project '${project.fullName}' and it parents, configure it first", HttpServletResponse.SC_NOT_FOUND) //TODO: Add link, good UI.
            }
            // Let's use connection from most nested project. (connections sorted in reverse project hierarchy order)
            connection = connections.first()
        }

        var postponedResult: JsonElement? = null

        attempts@
        for (i in 0..2) {
            val tokens = myTokensHelper.getExistingTokens(connections, user)
            if (tokens.isEmpty()) {
                // obtain access token
                LOG.info("No token found will try to obtain one using connection ${connection.id}")

                if (action == "continue") {
                    // Already from "/oauth/github/accessToken.html", cannot do anything else.
                    postponedResult = error_json("Cannot find token in connection ${connection.connectionDisplayName}.\nEnsure connection configured correctly", HttpServletResponse.SC_NOT_FOUND)
                    continue@attempts
                }
                val params = linkedMapOf("action" to "continue", "original_action" to action, "popup" to popup, "type" to inType, "id" to inId, "connectionId" to connection.id, "connectionProjectId" to connection.project.externalId)
                if (inProjectId != null) {
                    params.put("projectId", inProjectId)
                }
                return redirect_json(url(request.contextPath + "/oauth/github/accessToken.html",
                        "action" to "obtainToken",
                        "connectionId" to connection.id,
                        "projectId" to connection.project.projectId,
                        "scope" to "write:repo_hook",
                        "callbackUrl" to url(request.contextPath + PATH, params))
                )
            }

            for (entry in tokens) {
                val ghc: GitHubClientEx = GitHubClientFactory.createGitHubClient(entry.key.parameters[GitHubConstants.GITHUB_URL_PARAM]!!)
                for (token in entry.value) {
                    LOG.info("Trying with token: ${token.oauthLogin}, connector is ${entry.key.id}")
                    ghc.setOAuth2Token(token.accessToken)

                    try {
                        val result = myWebHooksManager.doRegisterWebHook(info, ghc)
                        val repoId = info.toString()
                        when (result) {
                            WebHooksManager.HookAddOperationResult.InvalidCredentials -> {
                                LOG.warn("Removing incorrect (outdated) token (user:${token.oauthLogin}, scope:${token.scope})")
                                myOAuthTokensStorage.removeToken(entry.key.id, token.accessToken)
                            }
                            WebHooksManager.HookAddOperationResult.TokenScopeMismatch -> {
                                LOG.warn("Token (user:${token.oauthLogin}, scope:${token.scope}) have not enough scope")
                                // TODO: Update token scope
                                myTokensHelper.markTokenIncorrect(token)
                                return gh_json(result.name, "Token scope does not cover hooks management", info)
                            }
                            WebHooksManager.HookAddOperationResult.AlreadyExists -> {
                                return gh_json(result.name, "Hook for repository '$repoId' already exits, updated info", info)
                            }
                            WebHooksManager.HookAddOperationResult.Created -> {
                                return gh_json(result.name, "Created hook for repository '$repoId'", info)
                            }
                            WebHooksManager.HookAddOperationResult.NoAccess -> {
                                return gh_json(result.name, "No access to repository '$repoId'", info)
                            }
                            WebHooksManager.HookAddOperationResult.UserHaveNoAccess -> {
                                return gh_json(result.name, "You don't have access to '$repoId'", info)
                            }
                        }
                    } catch(e: RequestException) {
                        LOG.warnAndDebugDetails("Unexpected response from github server", e)
                    } catch(e: IOException) {
                        LOG.warnAndDebugDetails("IOException instead of response from github server", e)
                    }
                }
            }
        }

        return postponedResult ?: gh_json("", "", info)
    }


    private fun doHandleCheckAction(request: HttpServletRequest, response: HttpServletResponse, action: String, popup: Boolean): JsonElement {
        TODO("Implement")
    }

    private fun doHandleCheckAllAction(request: HttpServletRequest, response: HttpServletResponse, action: String, popup: Boolean): JsonElement {
        TODO("Implement")
    }

    private fun doHandleDeleteAction(request: HttpServletRequest, response: HttpServletResponse, action: String, popup: Boolean): JsonElement {
        TODO("Implement")
    }

    @Throws(RequestException::class)
    private fun getConnection(request: HttpServletRequest, inProjectId: String?): OAuthConnectionDescriptor? {
        val inConnectionId = request.getParameter("connectionId")
        val inConnectionProjectId = request.getParameter("connectionProjectId") ?: inProjectId
        if (inConnectionId == null || inConnectionProjectId == null) {
            return null
        }
        val connectionOwnerProject = myProjectManager.findProjectByExternalId(inConnectionProjectId)
        @Suppress("IfNullToElvis")
        if (connectionOwnerProject == null) {
            throw RequestException("There no project with external id $inConnectionProjectId", HttpServletResponse.SC_NOT_FOUND)
        }
        val connection = myOAuthConnectionsManager.findConnectionById(connectionOwnerProject, inConnectionId)
        @Suppress("IfNullToElvis")
        if (connection == null) {
            throw RequestException("There no connection with id '$inConnectionId' found in project ${connectionOwnerProject.fullName}", HttpServletResponse.SC_NOT_FOUND)
        }
        return connection
    }

    @Throws(RequestException::class)
    fun getRepositoryInfo(inProjectId: String?, inId: String): Pair<SProject, VcsRootGitHubInfo> {
        if (inProjectId.isNullOrEmpty()) {
            throw RequestException("Required parameter 'projectId' is missing", HttpServletResponse.SC_BAD_REQUEST)
        }
        var project = myProjectManager.findProjectByExternalId(inProjectId) ?: throw RequestException("There no project with external id $inProjectId", HttpServletResponse.SC_NOT_FOUND)
        var info = Util.Companion.getGitHubInfo(inId) ?: throw RequestException("Not an GitHub VCS", HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        return project to info
    }

}

fun error_json(message: String, @MagicConstant(flagsFromClass = HttpServletResponse::class) code: Int): JsonElement {
    val obj = JsonObject()
    obj.addProperty("error", message)
    obj.addProperty("code", code)
    return obj
}

fun redirect_json(url: String): JsonElement {
    val obj = JsonObject()
    obj.addProperty("redirect", url)
    return obj
}

private fun url(url: String, vararg params: Pair<String, Any>): String {
    return url(url, params.associateBy({ it -> it.first }, { it -> it.second.toString() }))
}

private fun url(url: String, params: Map<String, Any>): String {
    val sb = StringBuilder()
    sb.append(url)
    if (!params.isEmpty()) sb.append('?')
    for (e in params.entries) {
        sb.append(e.key).append('=').append(WebUtil.encode(e.value.toString())).append('&')
    }
    return sb.toString()
}

fun gh_json(result: String, message: String, info: VcsRootGitHubInfo): JsonElement {
    val obj = JsonObject()
    obj.addProperty("result", result)
    obj.addProperty("message", message)
    obj.add("info", Gson().toJsonTree(info))
    return obj
}