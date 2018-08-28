package com.accolite.pru.health.AuthApp.controller;

import com.accolite.pru.health.AuthApp.event.OnRegenerateEmailVerificationEvent;
import com.accolite.pru.health.AuthApp.event.OnUserRegistrationCompleteEvent;
import com.accolite.pru.health.AuthApp.exception.InvalidTokenRequestException;
import com.accolite.pru.health.AuthApp.exception.UserLoginException;
import com.accolite.pru.health.AuthApp.exception.UserRegistrationException;
import com.accolite.pru.health.AuthApp.model.User;
import com.accolite.pru.health.AuthApp.model.payload.ApiResponse;
import com.accolite.pru.health.AuthApp.model.payload.JwtAuthenticationResponse;
import com.accolite.pru.health.AuthApp.model.payload.LoginRequest;
import com.accolite.pru.health.AuthApp.model.payload.RegistrationRequest;
import com.accolite.pru.health.AuthApp.model.token.EmailVerificationToken;
import com.accolite.pru.health.AuthApp.security.JwtTokenProvider;
import com.accolite.pru.health.AuthApp.service.AuthService;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import javax.validation.Valid;
import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	@Autowired
	private AuthService authService;

	private static final Logger logger = Logger.getLogger(AuthController.class);

	@Autowired
	private JwtTokenProvider tokenProvider;

	@Autowired
	private ApplicationEventPublisher applicationEventPublisher;

	/**
	 * Entry point for the user log in
	 */
	@PostMapping("/login")
	public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
		Optional<Authentication> authenticationOpt = authService.authenticateUser(loginRequest);
		authenticationOpt.orElseThrow(() -> new UserLoginException("Couldn't login user [" + loginRequest + "]"));
		Authentication authentication = authenticationOpt.get();

		logger.info("Logged in User returned [API]: " + authentication.getName());
		SecurityContextHolder.getContext().setAuthentication(authentication);
		String jwtToken = tokenProvider.generateToken(authentication);
		return ResponseEntity.ok(new JwtAuthenticationResponse(jwtToken));
	}

	/**
	 * Entry point for the user registration process. On successful registration, publish
	 * an event to generate email verification token
	 */
	@PostMapping("/register")
	public ResponseEntity<?> registerUser(@Valid @RequestBody RegistrationRequest registrationRequest,
			WebRequest request) {
		Optional<User> registeredUserOpt = authService.registerUser(registrationRequest);
		registeredUserOpt.orElseThrow(() -> new UserRegistrationException("Couldn't register user [" + registrationRequest +
				"]"));
		User registeredUser = registeredUserOpt.get();
		UriComponentsBuilder urlBuilder = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/auth" +
				"/registrationConfirmation");

		OnUserRegistrationCompleteEvent onUserRegistrationCompleteEvent =
				new OnUserRegistrationCompleteEvent(registeredUser, urlBuilder);
		applicationEventPublisher.publishEvent(onUserRegistrationCompleteEvent);

		logger.info("Executing main service in thread: " + Thread.currentThread());
		logger.info("Registered User returned [API[: " + registeredUser);
		URI location = ServletUriComponentsBuilder
				.fromCurrentContextPath().path("/api/user/me")
				.buildAndExpand(registeredUser.getEmail()).toUri();

		return ResponseEntity.created(location).body(new ApiResponse("User registered successfully. Check your email" +
				" for verification", true));
	}


	/**
	 * Confirm the email verification token generated for the user during registration. If
	 * token is invalid or token is expired, report error.
	 */
	@GetMapping("/registrationConfirmation")
	public ResponseEntity<?> confirmRegistration(@RequestParam("token") String token) {
		Optional<User> verifiedUserOpt = authService.confirmRegistration(token);
		verifiedUserOpt.orElseThrow(() -> new InvalidTokenRequestException("Email Verification Token", token,
				"Failed to confirm. Please generate a new email verification request"));

		User verifiedUser = verifiedUserOpt.get();
		URI location = ServletUriComponentsBuilder
				.fromCurrentContextPath().path("/api/user/me")
				.buildAndExpand(verifiedUser.getEmail()).toUri();
		return ResponseEntity.created(location).body(new ApiResponse("User verified successfully", true));
	}

	/**
	 * Resend the email registration mail with an updated token expiry.
	 * Safe to assume that the user would always click on the last re-verification email
	 * and any attempts at generating new token from past (possibly archived/deleted) tokens
	 * should fail and report an exception.
	 */
	@GetMapping("/resendRegistrationToken")
	public ResponseEntity<?> resendRegistrationToken(@RequestParam("token") String existingToken) {
		Optional<EmailVerificationToken> newEmailTokenOpt = authService.recreateRegistrationToken(existingToken);
		newEmailTokenOpt.orElseThrow(() -> new InvalidTokenRequestException("Email Verification Token", existingToken,
				"User is already registered. No need to re-generate token"));

		User registeredUser = newEmailTokenOpt.map(EmailVerificationToken::getUser)
				.orElseThrow(() -> new InvalidTokenRequestException("Email Verification Token", existingToken,
						"No user associated with this request. Re-verification denied"));

		UriComponentsBuilder urlBuilder = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/auth" +
				"/registrationConfirmation");
		OnRegenerateEmailVerificationEvent regenerateEmailVerificationEvent =
				new OnRegenerateEmailVerificationEvent(registeredUser, urlBuilder, newEmailTokenOpt.get());
		applicationEventPublisher.publishEvent(regenerateEmailVerificationEvent);

		return ResponseEntity.ok(new ApiResponse("Email verification resent successfully", true));
	}


}