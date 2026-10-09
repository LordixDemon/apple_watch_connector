package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class ActivationChallengeCodecTest {
    private static final String PAGE =
            "<xmlui style=\"setupAssistant\">\n"
                    + "<page name=\"FMIPLockChallenge\">\n"
                    + "    <script>\n"
                    + "        <![CDATA[ function enableNext() { return true; } ]]>\n"
                    + "    </script>\n"
                    + "    <navigationBar align=\"center\" "
                    + "title=\"Apple Watch Locked to Owner\">\n"
                    + "        <linkBarItem id=\"next\" "
                    + "url=\"/deviceservices/deviceActivation\" "
                    + "position=\"right\" label=\"Next\" "
                    + "enabledFunction=\"enableNext\" httpMethod=\"POST\" />\n"
                    + "    </navigationBar>\n"
                    + "    <tableView>\n"
                    + "        <section><htmlHeader><![CDATA[ <body>"
                    + "<div class=\"bold\">l\u2022\u2022\u2022\u2022\u2022@gmail.com</div>"
                    + "</body> ]]></htmlHeader></section>\n"
                    + "        <section>\n"
                    + "            <editableTextRow id=\"login\" "
                    + "keyboardType=\"email\" value=\"\"/>\n"
                    + "            <editableTextRow id=\"password\" "
                    + "secure=\"true\"/>\n"
                    + "        </section>\n"
                    + "    </tableView>\n"
                    + "</page>\n"
                    + "<serverInfo activation-info-base64=\"QUJDREVGRw==\">\n"
                    + "</serverInfo>\n"
                    + "</xmlui>\n";

    /** Captured live 2026-09-30: Albert's answer to a malformed submit. */
    private static final String VERIFICATION_FAILED_PAGE =
            "<xmlui><page><navigationBar title=\"Verification Failed\" "
                    + "hidesBackButton=\"false\"/><tableView><section "
                    + "footer=\"Please retry activation.\"/><section>"
                    + "<buttonRow align=\"center\" label=\"Try Again\" "
                    + "name=\"tryAgain\" titleLoadingMessage=\"\"/>"
                    + "</section></tableView></page></xmlui>";

    /** Captured live 2026-09-30: credential evaluation reached. */
    private static final String ACCOUNT_DISABLED_ALERT =
            "<xmlui style=\"setupAssistant\"><alert "
                    + "title = \"Apple Account disabled\" "
                    + "message=\"Your account has been disabled for "
                    + "security reasons. To enable your account, reset "
                    + "your password at account.apple.com.\">\n"
                    + "    <cancelButton>OK</cancelButton>\n"
                    + "</alert>\n"
                    + "<clientInfo escrowResponse=\"\" "
                    + "activationURL=\"/deviceservices/deviceActivation\"/>"
                    + "</xmlui>";

    @Test
    public void detectsBuddymlChallengePage() {
        assertTrue(
                ActivationChallengeCodec.isChallengePage(
                        PAGE.getBytes(
                                StandardCharsets.UTF_8)));
    }

    @Test
    public void detectsVerificationFailedAndAlertPages() {
        // Regression, live 0.2.172: the field-less Verification Failed
        // page was classified as non-buddyml and forwarded to the Watch,
        // which answered "Failed to extract activation record".
        assertTrue(
                ActivationChallengeCodec.isChallengePage(
                        VERIFICATION_FAILED_PAGE.getBytes(
                                StandardCharsets.UTF_8)));
        assertTrue(
                ActivationChallengeCodec.isChallengePage(
                        ACCOUNT_DISABLED_ALERT.getBytes(
                                StandardCharsets.UTF_8)));
    }

    @Test
    public void rejectsPlistAndGarbage() {
        assertFalse(
                ActivationChallengeCodec.isChallengePage(
                        null));
        assertFalse(
                ActivationChallengeCodec.isChallengePage(
                        new byte[0]));
        assertFalse(
                ActivationChallengeCodec.isChallengePage(
                        ("<?xml version=\"1.0\"?><plist version=\"1.0\">"
                                + "<dict/></plist>").getBytes(
                                StandardCharsets.UTF_8)));
        assertFalse(
                ActivationChallengeCodec.isChallengePage(
                        "bplist00\u00d0\u0001\u0002".getBytes(
                                StandardCharsets.UTF_8)));
    }

    @Test
    public void parsesCapturedFmipLockChallenge() {
        ActivationChallengeCodec.Challenge challenge =
                ActivationChallengeCodec.parse(
                        PAGE.getBytes(
                                StandardCharsets.UTF_8));
        assertEquals(
                "FMIPLockChallenge",
                challenge.pageName);
        assertEquals(
                "l\u2022\u2022\u2022\u2022\u2022@gmail.com",
                challenge.maskedAccount);
        assertEquals(
                "QUJDREVGRw==",
                challenge.activationInfoBase64);
        assertEquals(
                "/deviceservices/deviceActivation",
                challenge.submitUrl);
        assertEquals(
                java.util.List.of(
                        "login",
                        "password"),
                challenge.fields);
    }

    @Test
    public void classifiesPageKinds() {
        ActivationChallengeCodec.PageInfo form =
                ActivationChallengeCodec.classify(
                        PAGE.getBytes(
                                StandardCharsets.UTF_8));
        assertEquals(
                ActivationChallengeCodec.PageKind.FORM,
                form.kind);
        assertEquals(
                "FMIPLockChallenge",
                form.pageName);

        ActivationChallengeCodec.PageInfo retry =
                ActivationChallengeCodec.classify(
                        VERIFICATION_FAILED_PAGE.getBytes(
                                StandardCharsets.UTF_8));
        assertEquals(
                ActivationChallengeCodec.PageKind.RETRY,
                retry.kind);
        assertEquals(
                "Verification Failed",
                retry.title);
        assertEquals(
                "Please retry activation.",
                retry.message);

        ActivationChallengeCodec.PageInfo alert =
                ActivationChallengeCodec.classify(
                        ACCOUNT_DISABLED_ALERT.getBytes(
                                StandardCharsets.UTF_8));
        assertEquals(
                ActivationChallengeCodec.PageKind.ALERT,
                alert.kind);
        assertEquals(
                "Apple Account disabled",
                alert.title);
        assertTrue(
                alert.message.contains(
                        "account.apple.com"));
    }

    @Test
    public void buildsFormBodyWithSingleActivationInfoField() {
        // Regression, live 0.2.172 + direct Albert probes: sending both
        // activation-info and activation-info-base64 makes Albert answer
        // "Verification Failed"; a single activation-info-base64 field
        // reaches credential evaluation.
        ActivationChallengeCodec.Challenge challenge =
                ActivationChallengeCodec.parse(
                        PAGE.getBytes(
                                StandardCharsets.UTF_8));
        Map<String, char[]> values =
                new LinkedHashMap<>();
        values.put(
                "login",
                "owner@example.com".toCharArray());
        values.put(
                "password",
                "s3cret+pw".toCharArray());
        byte[] body =
                ActivationChallengeCodec.buildSubmitBody(
                        challenge,
                        values);
        String form =
                new String(
                        body,
                        StandardCharsets.UTF_8);
        assertTrue(
                form.contains(
                        "activation-info-base64=QUJDREVGRw%3D%3D"));
        assertFalse(
                form.contains(
                        "activation-info="));
        assertTrue(
                form.contains(
                        "login=owner%40example.com"));
        assertTrue(
                form.contains(
                        "password=s3cret%2Bpw"));
    }

    @Test
    public void buildsRetryBodyFromServerInfoEcho() {
        byte[] body =
                ActivationChallengeCodec.buildRetryBody(
                        "QUJDREVGRw==");
        String form =
                new String(
                        body,
                        StandardCharsets.UTF_8);
        // "QUJDREVGRw==" decodes to "ABCDEFG"; the retry body carries the
        // decoded XML plist under the single activation-info field.
        assertEquals(
                "activation-info=ABCDEFG",
                form);
    }

    @Test
    public void rejectsPageWithoutActivationInfo() {
        String broken =
                PAGE.replace(
                        "activation-info-base64=\"QUJDREVGRw==\"",
                        "");
        assertThrows(
                IllegalArgumentException.class,
                () -> ActivationChallengeCodec.parse(
                        broken.getBytes(
                                StandardCharsets.UTF_8)));
    }

    @Test
    public void rejectsPageWithoutSubmitUrl() {
        String broken =
                PAGE.replace(
                        "url=\"/deviceservices/deviceActivation\" ",
                        "");
        assertThrows(
                IllegalArgumentException.class,
                () -> ActivationChallengeCodec.parse(
                        broken.getBytes(
                                StandardCharsets.UTF_8)));
    }
}
