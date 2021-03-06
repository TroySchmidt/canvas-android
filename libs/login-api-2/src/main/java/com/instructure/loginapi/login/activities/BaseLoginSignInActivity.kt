/*
 * Copyright (C) 2017 - present Instructure, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.instructure.loginapi.login.activities

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import com.instructure.canvasapi2.RequestInterceptor.Companion.acceptedLanguageString
import com.instructure.canvasapi2.StatusCallback
import com.instructure.canvasapi2.managers.OAuthManager.getToken
import com.instructure.canvasapi2.managers.UserManager.getSelf
import com.instructure.canvasapi2.models.AccountDomain
import com.instructure.canvasapi2.models.OAuthTokenResponse
import com.instructure.canvasapi2.models.User
import com.instructure.canvasapi2.utils.*
import com.instructure.canvasapi2.utils.Analytics.logEvent
import com.instructure.canvasapi2.utils.ApiPrefs.accessToken
import com.instructure.canvasapi2.utils.ApiPrefs.clientId
import com.instructure.canvasapi2.utils.ApiPrefs.clientSecret
import com.instructure.canvasapi2.utils.ApiPrefs.domain
import com.instructure.canvasapi2.utils.ApiPrefs.protocol
import com.instructure.canvasapi2.utils.ApiPrefs.refreshToken
import com.instructure.canvasapi2.utils.ApiPrefs.user
import com.instructure.canvasapi2.utils.Logger.d
import com.instructure.loginapi.login.R
import com.instructure.loginapi.login.api.MobileVerifyAPI.mobileVerify
import com.instructure.loginapi.login.dialog.AuthenticationDialog
import com.instructure.loginapi.login.dialog.AuthenticationDialog.Companion.newInstance
import com.instructure.loginapi.login.dialog.AuthenticationDialog.OnAuthenticationSet
import com.instructure.loginapi.login.model.DomainVerificationResult
import com.instructure.loginapi.login.model.SignedInUser
import com.instructure.loginapi.login.snicker.SnickerDoodle
import com.instructure.loginapi.login.util.Const
import com.instructure.loginapi.login.util.Const.CANVAS_LOGIN_FLOW
import com.instructure.loginapi.login.util.Const.MASQUERADE_FLOW
import com.instructure.loginapi.login.util.Const.MOBILE_VERIFY_FLOW
import com.instructure.loginapi.login.util.Const.SNICKER_DOODLES
import com.instructure.loginapi.login.util.PreviousUsersUtils.add
import com.instructure.pandautils.utils.Utils
import com.instructure.pandautils.utils.ViewStyler.setStatusBarLight
import retrofit2.Call
import retrofit2.Response
import java.util.*

abstract class BaseLoginSignInActivity : AppCompatActivity(), OnAuthenticationSet {
    companion object {
        const val ACCOUNT_DOMAIN = "accountDomain"
        const val SUCCESS_URL = "/login/oauth2/auth?code="
        const val ERROR_URL = "/login/oauth2/auth?error=access_denied"

        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        }
    }

    protected abstract fun launchApplicationMainActivityIntent(): Intent
    protected abstract fun refreshWidgets()
    protected abstract fun userAgent(): String

    private lateinit var webView: WebView
    private var canvasLogin = 0
    var specialCase = false
    private var authenticationURL: String? = null
    private var httpAuthHandler: HttpAuthHandler? = null

    val accountDomain: AccountDomain by lazy { intent.getParcelableExtra<AccountDomain>(ACCOUNT_DOMAIN) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)
        canvasLogin = intent!!.extras!!.getInt(Const.CANVAS_LOGIN, 0)
        setupViews()
        applyTheme()
        beginSignIn(accountDomain)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupViews() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = accountDomain.domain
        toolbar.setNavigationIcon(R.drawable.ic_action_arrow_back)
        toolbar.navigationIcon?.isAutoMirrored = true
        toolbar.setNavigationContentDescription(R.string.close)
        toolbar.setNavigationOnClickListener { finish() }
        webView = findViewById(R.id.webView)
        clearCookies()
        CookieManager.getInstance().setAcceptCookie(true)
        webView.settings.loadWithOverviewMode = true
        webView.settings.javaScriptEnabled = true
        webView.settings.builtInZoomControls = true
        webView.settings.useWideViewPort = true
        @Suppress("DEPRECATION")
        webView.settings.saveFormData = false
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.setAppCacheEnabled(false)
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = Utils.generateUserAgent(this, userAgent())
        webView.webViewClient = mWebViewClient
        if (0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun applyTheme() {
        setStatusBarLight(this)
    }

    /**
     * Override to handle the shouldOverrideUrlLoading() method.
     * @param view WebView
     * @param url Url String
     * @return If overriding this method it is expected to return true, if false the default behavior
     * of the BaseLoginSignInActivity will handle the override.
     */
    @Suppress("UNUSED_PARAMETER")
    protected fun overrideUrlLoading(view: WebView?, url: String?): Boolean {
        return false
    }

    private val mWebViewClient: WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return handleShouldOverrideUrlLoading(view, request.url.toString())
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return handleShouldOverrideUrlLoading(view, url)
        }

        private fun handleShouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (overrideUrlLoading(view, url)) return true
            when {
                url.contains(SUCCESS_URL) -> {
                    domain = accountDomain.domain!!
                    val oAuthRequest = url.substring(url.indexOf(SUCCESS_URL) + SUCCESS_URL.length)
                    getToken(clientId, clientSecret, oAuthRequest, mGetTokenCallback)
                }
                url.contains(ERROR_URL) -> {
                    clearCookies()
                    view.loadUrl(authenticationURL, headers)
                }
                else -> view.loadUrl(url, headers)
            }
            return true // then it is not handled by default action
        }

        @Suppress("DEPRECATION")
        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
            handleShouldInterceptRequest(url)
            return super.shouldInterceptRequest(view, url)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            handleShouldInterceptRequest(request.url.toString())
            return super.shouldInterceptRequest(view, request)
        }

        private fun handleShouldInterceptRequest(url: String) {
            if (url.contains("idp.sfcollege.edu/idp/santafe")) {
                specialCase = true
                domain = accountDomain.domain!!
                val oAuthRequest = url.substringAfter("hash=")
                getToken(clientId, clientSecret, oAuthRequest, mGetTokenCallback)
            }
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            httpAuthHandler = handler
            newInstance(accountDomain.domain).show(supportFragmentManager, AuthenticationDialog::class.java.simpleName)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest?, errorResponse: WebResourceResponse) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (errorResponse.statusCode == 400 && authenticationURL != null && request != null && request.url != null && authenticationURL == request.url.toString()) {
                    //If the institution does not support skipping the authentication screen this will catch that error and force the
                    //rebuilding of the authentication url with the authorization screen flow. Example: canvas.sfu.ca
                    buildAuthenticationUrl(protocol, accountDomain, clientId, true)
                    loadAuthenticationUrl(protocol, accountDomain.domain)
                }
            }
            super.onReceivedHttpError(view, request, errorResponse)
        }
    }

    private fun beginSignIn(accountDomain: AccountDomain) {
        val url = accountDomain.domain
        if (canvasLogin == MOBILE_VERIFY_FLOW) { //Skip Mobile Verify
            val view = LayoutInflater.from(this@BaseLoginSignInActivity).inflate(R.layout.dialog_skip_mobile_verify, null)
            val protocolEditText = view.findViewById<EditText>(R.id.mobileVerifyProtocol)
            val clientIdEditText = view.findViewById<EditText>(R.id.mobileVerifyClientId)
            val clientSecretEditText = view.findViewById<EditText>(R.id.mobileVerifyClientSecret)
            val dialog = AlertDialog.Builder(this@BaseLoginSignInActivity)
                .setTitle(R.string.mobileVerifyDialogTitle)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    protocol = protocolEditText.text.toString()
                    domain = url!!
                    clientId = clientIdEditText.text.toString()
                    clientSecret = clientSecretEditText.text.toString()
                    buildAuthenticationUrl(
                        protocolEditText.text.toString(),
                        accountDomain,
                        clientId,
                        false
                    )
                    webView.loadUrl(authenticationURL, headers)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> mobileVerify(url, mobileVerifyCallback) }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(Color.BLACK)
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(Color.BLACK)
            }
            dialog.show()
        } else {
            mobileVerify(url, mobileVerifyCallback)
        }
    }

    override fun onRetrieveCredentials(username: String?, password: String?) {
        if (username.isValid() && password.isValid()) {
            httpAuthHandler?.proceed(username, password)
        } else {
            Toast.makeText(applicationContext, R.string.invalidEmailPassword, Toast.LENGTH_SHORT).show()
        }
    }

    protected fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
    }

    val headers: Map<String, String>
        get() = mapOf(
            "accept-language" to acceptedLanguageString,
            "user-agent" to Utils.generateUserAgent(this, userAgent()),
            "session_locale" to Locale.getDefault().language
        )

    //region Callbacks
    private var mobileVerifyCallback: StatusCallback<DomainVerificationResult> =
        object : StatusCallback<DomainVerificationResult>() {
            override fun onResponse(response: Response<DomainVerificationResult>, linkHeaders: LinkHeaders, type: ApiType) {
                if (type.isCache) return
                val domainVerificationResult = response.body()
                if (domainVerificationResult!!.result === DomainVerificationResult.DomainVerificationCode.Success) {
                    //Domain is now verified.
                    //save domain to the preferences.
                    var domain: String?

                    //mobile verify can change the hostname we need to use
                    domainVerificationResult!!.baseUrl
                    domain = if (domainVerificationResult.baseUrl != "") {
                        domainVerificationResult.baseUrl
                    } else {
                        accountDomain.domain
                    }
                    if (domain!!.endsWith("/")) {
                        domain = domain.substring(0, domain.length - 1)
                    }
                    accountDomain.domain = domain
                    clientId = domainVerificationResult.clientId
                    clientSecret = domainVerificationResult.clientSecret

                    //Get the protocol
                    val apiProtocol = domainVerificationResult.protocol

                    //Set the protocol
                    protocol = domainVerificationResult.protocol
                    buildAuthenticationUrl(apiProtocol, accountDomain, clientId, false)
                    loadAuthenticationUrl(apiProtocol, domain)
                } else {
                    //Error message
                    val errorId: Int = when (domainVerificationResult?.result) {
                        DomainVerificationResult.DomainVerificationCode.GeneralError -> R.string.mobileVerifyGeneral
                        DomainVerificationResult.DomainVerificationCode.DomainNotAuthorized -> R.string.mobileVerifyDomainUnauthorized
                        DomainVerificationResult.DomainVerificationCode.UnknownUserAgent -> R.string.mobileVerifyUserAgentUnauthorized
                        else -> R.string.mobileVerifyUnknownError
                    }
                    if (!this@BaseLoginSignInActivity.isFinishing) {
                        val builder = AlertDialog.Builder(this@BaseLoginSignInActivity)
                        builder.setTitle(R.string.errorOccurred)
                        builder.setMessage(errorId)
                        builder.setCancelable(true)
                        val dialog = builder.create()
                        dialog.show()
                    }
                }
            }
        }

    protected fun loadAuthenticationUrl(apiProtocol: String, domain: String?) {
        if (canvasLogin == CANVAS_LOGIN_FLOW) {
            authenticationURL += "&canvas_login=1"
        } else if (canvasLogin == MASQUERADE_FLOW) {
            // canvas_sa_delegated=1    identifies that we want to masquerade
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            if (domain!!.contains(".instructure.com")) {
                val cookie = "canvas_sa_delegated=1;domain=.instructure.com;path=/;"
                cookieManager.setCookie("$apiProtocol://$domain", cookie)
                cookieManager.setCookie(".instructure.com", cookie)
            } else {
                cookieManager.setCookie(domain, "canvas_sa_delegated=1")
            }
        }
        webView.loadUrl(authenticationURL, headers)
        if (0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            webView.postDelayed({
                if (intent.hasExtra(SNICKER_DOODLES)) {
                    val snickerDoodle: SnickerDoodle = intent.getParcelableExtra(SNICKER_DOODLES)
                    populateWithSnickerDoodle(snickerDoodle)
                }
            }, 1500)
        }
    }

    protected fun buildAuthenticationUrl(protocol: String?, accountDomain: AccountDomain?, clientId: String?, forceAuthRedirect: Boolean) {
        //Get device name for the login request.
        var deviceName = Build.MODEL
        if (deviceName == null || deviceName == "") {
            deviceName = getString(R.string.unknownDevice)
        }
        // Remove spaces
        deviceName = deviceName!!.replace(" ", "_")
        // Changed for the online update to have an actual formatted login page
        var domain = accountDomain!!.domain
        if (domain != null && domain.endsWith("/")) {
            domain = domain.substring(0, domain.length - 1)
        }
        val builder = Uri.Builder()
            .scheme(protocol)
            .authority(domain)
            .appendPath("login")
            .appendPath("oauth2")
            .appendPath("auth")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("mobile", "1")
            .appendQueryParameter("purpose", deviceName)
        if (forceAuthRedirect || canvasLogin == MOBILE_VERIFY_FLOW || domain != null && domain.contains(".test.")) {
            //Skip mobile verify
            builder.appendQueryParameter("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
        } else {
            builder.appendQueryParameter("redirect_uri", "https://canvas.instructure.com/login/oauth2/auth")
        }

        //If an authentication provider is supplied we need to pass that along. This should only be appended if one exists.
        val authenticationProvider = accountDomain.authenticationProvider
        if (authenticationProvider != null && authenticationProvider.isNotEmpty() && !authenticationProvider.equals("null", ignoreCase = true)) {
            d("authentication_provider=$authenticationProvider")
            builder.appendQueryParameter("authentication_provider", authenticationProvider)
        }
        val authUri = builder.build()
        if (0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            d("AUTH URL: $authUri")
        }
        authenticationURL = authUri.toString()
    }

    private val mGetTokenCallback: StatusCallback<OAuthTokenResponse> =
        object : StatusCallback<OAuthTokenResponse>() {
            override fun onResponse(response: Response<OAuthTokenResponse>, linkHeaders: LinkHeaders, type: ApiType) {
                if (type.isCache) return
                val bundle = Bundle()
                bundle.putString(AnalyticsParamConstants.DOMAIN_PARAM, domain)
                logEvent(AnalyticsEventConstants.LOGIN_SUCCESS, bundle)
                val token = response.body()
                refreshToken = token!!.refreshToken!!
                accessToken = token.accessToken!!
                @Suppress("DEPRECATION")
                ApiPrefs.token = "" // TODO: Remove when we're 100% using refresh tokens

                // We now need to get the cache user
                getSelf(object : StatusCallback<User>() {
                    override fun onResponse(response: Response<User>, linkHeaders: LinkHeaders, type: ApiType) {
                        if (type.isAPI) {
                            user = response.body()
                            val userResponse = response.body()
                            val domain = domain
                            val protocol = protocol
                            val user = SignedInUser(
                                userResponse!!,
                                domain,
                                protocol,
                                "",  // TODO - delete once we move over 100% to refresh tokens
                                token.accessToken!!,
                                token.refreshToken!!,
                                clientId,
                                clientSecret,
                                null,
                                null
                            )
                            add(this@BaseLoginSignInActivity, user)
                            refreshWidgets()
                            handleLaunchApplicationMainActivityIntent()
                        }
                    }
                })
            }

            override fun onFail(call: Call<OAuthTokenResponse>?, error: Throwable, response: Response<*>?) {
                val bundle = Bundle()
                bundle.putString(AnalyticsParamConstants.DOMAIN_PARAM, domain)
                logEvent(AnalyticsEventConstants.LOGIN_FAILURE, bundle)
                if (!specialCase) {
                    Toast.makeText(
                        this@BaseLoginSignInActivity,
                        R.string.errorOccurred,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    specialCase = false
                }
                webView.loadUrl(authenticationURL, headers)
            }
        }

    /**
     * Override and do not call super if you need additional logic before launching the main activity intent.
     * It is expected that the class overriding will launch an intent.
     */
    protected fun handleLaunchApplicationMainActivityIntent() {
        val intent = launchApplicationMainActivityIntent()
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    //endregion
    //region Snicker Doodles
    private fun populateWithSnickerDoodle(snickerDoodle: SnickerDoodle) {
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        val js = "javascript: { " +
                "document.getElementsByName('pseudonym_session[unique_id]')[0].value = '" + snickerDoodle.username + "'; " +
                "document.getElementsByName('pseudonym_session[password]')[0].value = '" + snickerDoodle.password + "'; " +
                "document.getElementsByClassName('Button')[0].click(); " +
                "};"
        webView.evaluateJavascript(js) { }
        Handler().postDelayed({
            runOnUiThread {
                val javascript = "javascript: { " +
                        "document.getElementsByClassName('btn')[0].click();" +
                        "};"
                webView.evaluateJavascript(javascript) { }
            }
        }, 750)
    } //endregion
}
