var global = {
    mobileClient: false,
    savePermit: true,
    usd: 0,
    eur: 0
};

/**
 * Oauth2
 */

var OAUTH_CLIENT_ID = 'browser';

function oauthRedirectUri() {
	return location.origin + '/';
}

function randomUrlSafe(bytes) {
	var values = new Uint8Array(bytes);
	crypto.getRandomValues(values);
	return base64Url(values);
}

function base64Url(bytes) {
	var text = '';
	for (var i = 0; i < bytes.length; i++) {
		text += String.fromCharCode(bytes[i]);
	}
	return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Redirects to the authorization server (authorization code with PKCE S256). */
function startAuthorization() {
	var verifier = randomUrlSafe(48);
	var state = randomUrlSafe(16);
	sessionStorage.setItem('pkce_verifier', verifier);
	sessionStorage.setItem('oauth_state', state);

	crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)).then(function (digest) {
		location.href = 'uaa/oauth2/authorize?' + $.param({
			response_type: 'code',
			client_id: OAUTH_CLIENT_ID,
			scope: 'ui',
			redirect_uri: oauthRedirectUri(),
			state: state,
			code_challenge: base64Url(new Uint8Array(digest)),
			code_challenge_method: 'S256'
		});
	});
}

/** Exchanges the code returned in the query string for a token; returns true when the token was stored. */
function completeAuthorizationIfPresent() {

	var params = new URLSearchParams(location.search);
	var code = params.get('code');
	if (!code) {
		return false;
	}

	var expectedState = sessionStorage.getItem('oauth_state');
	var verifier = sessionStorage.getItem('pkce_verifier');
	sessionStorage.removeItem('oauth_state');
	sessionStorage.removeItem('pkce_verifier');
	history.replaceState(null, '', location.pathname);

	if (!verifier || !expectedState || params.get('state') !== expectedState) {
		removeOauthTokenFromStorage();
		return false;
	}

	var success = false;

	$.ajax({
		url: 'uaa/oauth2/token',
		datatype: 'json',
		type: 'post',
		async: false,
		data: {
			grant_type: 'authorization_code',
			client_id: OAUTH_CLIENT_ID,
			code: code,
			redirect_uri: oauthRedirectUri(),
			code_verifier: verifier
		},
		success: function (data) {
			localStorage.setItem('token', data.access_token);
			success = true;
		},
		error: function () {
			removeOauthTokenFromStorage();
		}
	});

	return success;
}

function getOauthTokenFromStorage() {
	return localStorage.getItem('token');
}

function removeOauthTokenFromStorage() {
    return localStorage.removeItem('token');
}

/**
 * Current account
 */

function getCurrentAccount() {

	var token = getOauthTokenFromStorage();
	var account = null;

	if (token) {
		$.ajax({
			url: 'accounts/current',
			datatype: 'json',
			type: 'get',
			headers: {'Authorization': 'Bearer ' + token},
			async: false,
			success: function (data) {
				account = data;
			},
			error: function () {
				removeOauthTokenFromStorage();
			}
		});
	}

	return account;
}

$(window).load(function(){

	if(/Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent) ) {
		FastClick.attach(document.body);
        global.mobileClient = true;
	}

    $.getJSON("https://api.exchangeratesapi.io/latest?base=RUB&symbols=EUR,USD", function( data ) {
        global.eur = 1 / data.rates.EUR;
        global.usd = 1 / data.rates.USD;
    });

	completeAuthorizationIfPresent();

	var account = getCurrentAccount();

	if (account) {
		showGreetingPage(account);
	} else {
		showLoginForm();
	}
});

function showGreetingPage(account) {
    initAccount(account);
	var userAvatar = $("<img />").attr("src","images/userpic.jpg");
	$(userAvatar).load(function() {
		setTimeout(initGreetingPage, 500);
	});
}

function showLoginForm() {
	$("#loginpage").show();
	$("#frontloginform").focus();
	setTimeout(initialShaking, 700);
}