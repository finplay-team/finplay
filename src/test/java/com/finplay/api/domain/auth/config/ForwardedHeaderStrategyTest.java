package com.finplay.api.domain.auth.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ForwardedHeaderFilter;

@WebMvcTest(controllers = ForwardedHeaderStrategyTest.SchemeProbeController.class, excludeAutoConfiguration = {
	ServletWebSecurityAutoConfiguration.class,
	SecurityFilterAutoConfiguration.class
})
@Import({
	ForwardedHeaderStrategyTest.SchemeProbeController.class,
	ForwardedHeaderStrategyTest.ForwardedHeaderFilterTestConfig.class
})
class ForwardedHeaderStrategyTest {

	private static final String PROBE_PATH = "/test/scheme-probe";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void treatsRequestAsSecureWhenAlbForwardsHttpsProto() throws Exception {
		mockMvc.perform(get(PROBE_PATH)
			.header("X-Forwarded-Proto", "https")
			.header("X-Forwarded-Host", "finplay.site"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.secure").value(true))
			.andExpect(jsonPath("$.scheme").value("https"));
	}

	@Test
	void doesNotForceSecureWithoutForwardedHeader() throws Exception {
		mockMvc.perform(get(PROBE_PATH))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.secure").value(false))
			.andExpect(jsonPath("$.scheme").value("http"));
	}

	@TestConfiguration
	static class ForwardedHeaderFilterTestConfig {

		@Bean
		FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
			FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(
				new ForwardedHeaderFilter());
			registration.setDispatcherTypes(DispatcherType.REQUEST);
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}
	}

	@RestController
	static class SchemeProbeController {

		@GetMapping(PROBE_PATH)
		Map<String, Object> probe(HttpServletRequest request) {
			return Map.of("secure", request.isSecure(), "scheme", request.getScheme());
		}
	}
}
