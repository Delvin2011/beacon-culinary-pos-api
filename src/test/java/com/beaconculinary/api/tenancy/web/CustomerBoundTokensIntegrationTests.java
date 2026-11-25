package com.beaconculinary.api.tenancy.web;

import com.beaconculinary.api.auth.JwtService;
import com.beaconculinary.api.auth.TenantBinding;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.tenancy.CustomerMvc;
import com.beaconculinary.api.support.tenancy.MultiCustomerFixture;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.users.UserRepository;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T1-5: a token is bound to the customer it was issued at. Issued at alpha and presented at bravo, an access token is
 * refused with 401 TENANT_MISMATCH on a back-office read, a POS write and the kitchen stream, and so is a refresh cookie;
 * at alpha both work as before. In multi mode a token without the customer (issued before T1-5) is refused. The refusal is
 * logged at WARN with the request id, never with the token. Credentials looked up in the customer's own database are
 * refused at bravo as before (here: an invite token).
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomerBoundTokensIntegrationTests extends MultiCustomerIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository users;

    private MultiCustomerFixture fixture;
    private MockMvc alpha;
    private MockMvc bravo;

    @BeforeEach
    void setUp() {
        fixture = MultiCustomerFixture.get();
        CustomerMvc.ensureAdmin(fixture.direct("alpha"));
        CustomerMvc.ensureAdmin(fixture.direct("bravo"));
        alpha = CustomerMvc.at(context, "alpha");
        bravo = CustomerMvc.at(context, "bravo");
    }

    @Test
    void anAccessTokenFromAlpha_isRefusedAtBravo_andWorksAtAlpha(CapturedOutput output) throws Exception {
        var token = AuthTestHelper.loginAsAdmin(alpha);

        var read = bravo.perform(get("/admin/staff").header("Authorization", "Bearer " + token)
                .header("X-Request-Id", "t15-mismatch-read")).andReturn().getResponse();
        expectMismatch(read);
        var write = bravo.perform(post("/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
        expectMismatch(write);
        var stream = bravo.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        assertThat(stream.getRequest().isAsyncStarted()).as("no stream opened at bravo").isFalse();
        expectMismatch(stream.getResponse());

        assertThat(alpha.perform(get("/admin/staff").header("Authorization", "Bearer " + token)).andReturn()
                .getResponse().getStatus()).as("at alpha, as before").isEqualTo(200);
        var alphaStream = alpha.perform(get("/kitchen/orders/stream").header("Authorization", "Bearer " + token)
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        assertThat(alphaStream.getRequest().isAsyncStarted()).isTrue();
        alphaStream.getRequest().getAsyncContext().complete();

        assertThat(output.getAll()).contains("TENANT_MISMATCH: a token issued for customer " + fixture.customer("alpha").id()
                + " was presented at customer bravo").contains("t15-mismatch-read");
        assertThat(output.getAll().contains(token)).as("the token appears in the output").isFalse();
    }

    @Test
    void aRefreshCookieFromAlpha_isRefusedAtBravo_andWorksAtAlpha(CapturedOutput output) throws Exception {
        var signIn = alpha.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(
                Map.of("email", AuthTestHelper.ADMIN_EMAIL, "password", "654321")))).andReturn();
        var cookie = refreshCookie(signIn);

        var atBravo = bravo.perform(post("/auth/refresh").cookie(cookie)).andReturn().getResponse();
        expectMismatch(atBravo);
        var atAlpha = alpha.perform(post("/auth/refresh").cookie(cookie)).andReturn().getResponse();
        assertThat(atAlpha.getStatus()).isEqualTo(200);
        assertThat(output.getAll()).contains("TENANT_MISMATCH: a refresh token issued for customer");
        assertThat(output.getAll().contains(cookie.getValue())).as("the refresh token appears in the output").isFalse();
    }

    @Test
    void aTokenWithoutTheCustomer_isRefusedInMultiMode() throws Exception {
        var admin = CustomerContext.callAs(fixture.customer("alpha"),
                () -> users.findByEmail(AuthTestHelper.ADMIN_EMAIL).orElseThrow());
        // Made with no current customer, as every token was before T1-5: no customer claim.
        var oldToken = jwtService.generateAccessToken(admin).toString();
        assertThat(jwtService.parseToken(oldToken).getTenantId()).isNull();

        expectMismatch(alpha.perform(get("/admin/staff").header("Authorization", "Bearer " + oldToken)).andReturn().getResponse());
    }

    @Test
    void aNewTokenCarriesItsCustomer() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(bravo);
        assertThat(jwtService.parseToken(token).getTenantId()).isEqualTo(fixture.customer("bravo").id());
    }

    @Test
    void anInviteTokenFromAlpha_meansNothingAtBravo() throws Exception {
        var admin = AuthTestHelper.loginAsAdmin(alpha);
        var tills = new O3Tills(alpha, fixture.direct("alpha"), "T15Inv", admin);
        var cashier = tills.createCashier();
        try {
            var issued = alpha.perform(post("/admin/staff/" + cashier.id() + "/invite").header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"STAFF_SETUP\"}")).andReturn();
            assertThat(issued.getResponse().getStatus()).as(issued.getResponse().getContentAsString()).isEqualTo(201);
            var inviteToken = MAPPER.readTree(issued.getResponse().getContentAsString()).get("token").asText();
            var body = MAPPER.writeValueAsString(Map.of("token", inviteToken));

            var atBravo = bravo.perform(post("/auth/invites/lookup").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            assertThat(atBravo.getStatus()).isEqualTo(404);
            assertThat(atBravo.getContentAsString()).contains("INVITE_INVALID");
            assertThat(alpha.perform(post("/auth/invites/lookup").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        } finally {
            tills.cleanUp();
        }
    }

    private static void expectMismatch(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"" + TenantBinding.CODE + "\"")
                .doesNotContain("alpha").doesNotContain("bravo");
    }

    private static Cookie refreshCookie(MvcResult signIn) {
        for (var header : signIn.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("refreshToken=")) {
                return new Cookie("refreshToken", header.substring("refreshToken=".length(), header.indexOf(';')));
            }
        }
        throw new AssertionError("no refresh cookie on sign-in");
    }
}
