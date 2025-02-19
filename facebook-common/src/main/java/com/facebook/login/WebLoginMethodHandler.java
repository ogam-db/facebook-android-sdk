/*
 * Copyright (c) 2014-present, Facebook, Inc. All rights reserved.
 *
 * You are hereby granted a non-exclusive, worldwide, royalty-free license to use,
 * copy, modify, and distribute this software in source code or binary form for use
 * in connection with the web services and APIs provided by Facebook.
 *
 * As with any software that integrates with the Facebook platform, your use of
 * this software is subject to the Facebook Developer Principles and Policies
 * [http://developers.facebook.com/policy/]. This copyright notice shall be
 * included in all copies or substantial portions of the software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.facebook.login;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.text.TextUtils;
import android.webkit.CookieSyncManager;
import com.facebook.AccessToken;
import com.facebook.AccessTokenSource;
import com.facebook.AuthenticationToken;
import com.facebook.FacebookException;
import com.facebook.FacebookOperationCanceledException;
import com.facebook.FacebookRequestError;
import com.facebook.FacebookSdk;
import com.facebook.FacebookServiceException;
import com.facebook.appevents.AppEventsConstants;
import com.facebook.internal.ServerProtocol;
import com.facebook.internal.Utility;
import java.util.Locale;

abstract class WebLoginMethodHandler extends LoginMethodHandler {
  private static final String WEB_VIEW_AUTH_HANDLER_STORE =
      "com.facebook.login.AuthorizationClient.WebViewAuthHandler.TOKEN_STORE_KEY";
  private static final String WEB_VIEW_AUTH_HANDLER_TOKEN_KEY = "TOKEN";

  protected AccessTokenSource tokenSource;

  protected String getRedirectUrl() {
    return "fb" + FacebookSdk.getApplicationId() + "://authorize/";
  }

  private String e2e;

  WebLoginMethodHandler(LoginClient loginClient) {
    super(loginClient);
  }

  WebLoginMethodHandler(Parcel source) {
    super(source);
  }

  abstract AccessTokenSource getTokenSource();

  protected String getSSODevice() {
    return null;
  }

  protected Bundle getParameters(final LoginClient.Request request) {
    Bundle parameters = new Bundle();
    if (!Utility.isNullOrEmpty(request.getPermissions())) {
      String scope = TextUtils.join(",", request.getPermissions());
      parameters.putString(ServerProtocol.DIALOG_PARAM_SCOPE, scope);
      addLoggingExtra(ServerProtocol.DIALOG_PARAM_SCOPE, scope);
    }

    DefaultAudience audience = request.getDefaultAudience();
    parameters.putString(
        ServerProtocol.DIALOG_PARAM_DEFAULT_AUDIENCE, audience.getNativeProtocolAudience());
    parameters.putString(ServerProtocol.DIALOG_PARAM_STATE, getClientState(request.getAuthId()));

    AccessToken previousToken = AccessToken.getCurrentAccessToken();
    String previousTokenString = previousToken != null ? previousToken.getToken() : null;
    if (previousTokenString != null && (previousTokenString.equals(loadCookieToken()))) {
      parameters.putString(ServerProtocol.DIALOG_PARAM_ACCESS_TOKEN, previousTokenString);
      // Don't log the actual access token, just its presence or absence.
      addLoggingExtra(
          ServerProtocol.DIALOG_PARAM_ACCESS_TOKEN, AppEventsConstants.EVENT_PARAM_VALUE_YES);
    } else {
      // The call to clear cookies will create the first instance of CookieSyncManager if
      // necessary
      Utility.clearFacebookCookies(getLoginClient().getActivity());
      addLoggingExtra(
          ServerProtocol.DIALOG_PARAM_ACCESS_TOKEN, AppEventsConstants.EVENT_PARAM_VALUE_NO);
    }

    parameters.putString(
        ServerProtocol.DIALOG_PARAM_CBT, String.valueOf(System.currentTimeMillis()));
    parameters.putString(
        ServerProtocol.DIALOG_PARAM_IES, FacebookSdk.getAutoLogAppEventsEnabled() ? "1" : "0");

    return parameters;
  }

  protected Bundle addExtraParameters(Bundle parameters, final LoginClient.Request request) {
    parameters.putString(ServerProtocol.DIALOG_PARAM_REDIRECT_URI, this.getRedirectUrl());
    if (request.isInstagramLogin()) {
      parameters.putString(ServerProtocol.DIALOG_PARAM_APP_ID, request.getApplicationId());
    } else {
      // Client id is a legacy name. IG Login doesn't support it. This line is kept
      // for FB Login for consistency with old SDKs
      parameters.putString(ServerProtocol.DIALOG_PARAM_CLIENT_ID, request.getApplicationId());
    }

    parameters.putString(ServerProtocol.DIALOG_PARAM_E2E, getLoginClient().getE2E());

    if (request.isInstagramLogin()) {
      parameters.putString(
          ServerProtocol.DIALOG_PARAM_RESPONSE_TYPE,
          ServerProtocol.DIALOG_RESPONSE_TYPE_TOKEN_AND_SCOPES);
    } else {
      if (request.getPermissions().contains(LoginConfiguration.OPENID)) {
        parameters.putString(
            ServerProtocol.DIALOG_PARAM_RESPONSE_TYPE,
            ServerProtocol.DIALOG_RESPONSE_TYPE_ID_TOKEN_AND_SIGNED_REQUEST);
        parameters.putString(ServerProtocol.DIALOG_PARAM_NONCE, request.getNonce());
      } else {
        parameters.putString(
            ServerProtocol.DIALOG_PARAM_RESPONSE_TYPE,
            ServerProtocol.DIALOG_RESPONSE_TYPE_TOKEN_AND_SIGNED_REQUEST);
      }
    }
    parameters.putString(
        ServerProtocol.DIALOG_PARAM_RETURN_SCOPES, ServerProtocol.DIALOG_RETURN_SCOPES_TRUE);
    parameters.putString(ServerProtocol.DIALOG_PARAM_AUTH_TYPE, request.getAuthType());
    parameters.putString(
        ServerProtocol.DIALOG_PARAM_LOGIN_BEHAVIOR, request.getLoginBehavior().name());
    parameters.putString(
        ServerProtocol.DIALOG_PARAM_SDK_VERSION,
        String.format(Locale.ROOT, "android-%s", FacebookSdk.getSdkVersion()));
    if (getSSODevice() != null) {
      parameters.putString(ServerProtocol.DIALOG_PARAM_SSO_DEVICE, getSSODevice());
    }
    parameters.putString(
        ServerProtocol.DIALOG_PARAM_CUSTOM_TABS_PREFETCHING,
        FacebookSdk.hasCustomTabsPrefetching ? "1" : "0");

    if (request.isFamilyLogin()) {
      parameters.putString(
          ServerProtocol.DIALOG_PARAM_FX_APP, request.getLoginTargetApp().toString());
    }

    if (request.shouldSkipAccountDeduplication()) {
      parameters.putString(ServerProtocol.DIALOG_PARAM_SKIP_DEDUPE, "true");
    }

    // Set Login Connect parameters if they are present
    if (request.getMessengerPageId() != null) {
      parameters.putString(
          ServerProtocol.DIALOG_PARAM_MESSENGER_PAGE_ID, request.getMessengerPageId());
      parameters.putString(
          ServerProtocol.DIALOG_PARAM_RESET_MESSENGER_STATE,
          request.getResetMessengerState() ? "1" : "0");
    }

    return parameters;
  }

  protected void onComplete(LoginClient.Request request, Bundle values, FacebookException error) {
    LoginClient.Result outcome;
    LoginClient loginClient = getLoginClient();
    e2e = null;
    if (values != null) {
      // Actual e2e we got from the dialog should be used for logging.
      if (values.containsKey(ServerProtocol.DIALOG_PARAM_E2E)) {
        e2e = values.getString(ServerProtocol.DIALOG_PARAM_E2E);
      }

      try {
        AccessToken token =
            createAccessTokenFromWebBundle(
                request.getPermissions(), values, getTokenSource(), request.getApplicationId());
        AuthenticationToken authenticationToken =
            createAuthenticationTokenFromWebBundle(values, request.getNonce());
        outcome =
            LoginClient.Result.createCompositeTokenResult(
                loginClient.getPendingRequest(), token, authenticationToken);

        // Ensure any cookies set by the dialog are saved
        // This is to work around a bug where CookieManager may fail to instantiate if
        // CookieSyncManager has never been created.
        CookieSyncManager syncManager = CookieSyncManager.createInstance(loginClient.getActivity());
        syncManager.sync();
        if (token != null) {
          saveCookieToken(token.getToken());
        }
      } catch (FacebookException ex) {
        outcome =
            LoginClient.Result.createErrorResult(
                loginClient.getPendingRequest(), null, ex.getMessage());
      }
    } else {
      if (error instanceof FacebookOperationCanceledException) {
        outcome =
            LoginClient.Result.createCancelResult(
                loginClient.getPendingRequest(), "User canceled log in.");
      } else {
        // Something went wrong, don't log a completion event since it will skew timing
        // results.
        e2e = null;

        String errorCode = null;
        String errorMessage = error.getMessage();
        if (error instanceof FacebookServiceException) {
          FacebookRequestError requestError = ((FacebookServiceException) error).getRequestError();
          errorCode = String.format(Locale.ROOT, "%d", requestError.getErrorCode());
          errorMessage = requestError.toString();
        }
        outcome =
            LoginClient.Result.createErrorResult(
                loginClient.getPendingRequest(), null, errorMessage, errorCode);
      }
    }

    if (!Utility.isNullOrEmpty(e2e)) {
      logWebLoginCompleted(e2e);
    }

    loginClient.completeAndValidate(outcome);
  }

  private String loadCookieToken() {
    Context context = getLoginClient().getActivity();
    SharedPreferences sharedPreferences =
        context.getSharedPreferences(WEB_VIEW_AUTH_HANDLER_STORE, Context.MODE_PRIVATE);
    return sharedPreferences.getString(WEB_VIEW_AUTH_HANDLER_TOKEN_KEY, "");
  }

  private void saveCookieToken(String token) {
    Context context = getLoginClient().getActivity();
    context
        .getSharedPreferences(WEB_VIEW_AUTH_HANDLER_STORE, Context.MODE_PRIVATE)
        .edit()
        .putString(WEB_VIEW_AUTH_HANDLER_TOKEN_KEY, token)
        .apply();
  }
}
