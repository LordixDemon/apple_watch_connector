package dev.applewatchandroid.bridge;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Albert "buddyml" Setup Assistant challenge pages.
 *
 * <p>When the Watch is Activation-Locked (Find My), Albert answers the
 * deviceActivation POST with an {@code application/x-buddyml} page such as
 * {@code FMIPLockChallenge} instead of an activation record. A real iPhone
 * renders that page, collects the owner's Apple Account credentials and
 * re-POSTs the page fields together with the {@code activation-info-base64}
 * blob from the page's {@code serverInfo} element. Only the final
 * non-buddyml response is ever forwarded to the Watch.</p>
 *
 * <p>Live-verified 2026-09-30 (0.2.172 run plus direct Albert probes):
 * sending both {@code activation-info} and {@code activation-info-base64}
 * makes Albert answer the 266-byte "Verification Failed / Please retry
 * activation" buddyml page, because it parses {@code activation-info}
 * first and chokes on base64 content. A single {@code activation-info-base64}
 * field (or a single {@code activation-info} with the decoded XML plist)
 * passes validation and reaches credential evaluation. Alert pages
 * ({@code <alert title=...>}) and field-less retry pages are buddyml too
 * and must never be forwarded to the Watch.</p>
 *
 * <p>Credentials pass through this codec only as form-encoded bytes; the
 * codec never logs them and never persists them.</p>
 */
final class ActivationChallengeCodec {
    private static final Pattern PAGE_NAME =
            Pattern.compile("<page\\s+name=\"([^\"]+)\"");
    private static final Pattern BOLD_DIV =
            Pattern.compile("<div\\s+class=\"bold\">(.*?)</div>",
                    Pattern.DOTALL);
    private static final Pattern ACTIVATION_INFO =
            Pattern.compile("activation-info-base64=\"([^\"]+)\"");
    private static final Pattern SUBMIT_LINK =
            Pattern.compile("<linkBarItem[^>]*id=\"next\"[^>]*>");
    private static final Pattern ATTR_URL =
            Pattern.compile("url=\"([^\"]+)\"");
    private static final Pattern ATTR_METHOD =
            Pattern.compile("httpMethod=\"([^\"]+)\"");
    private static final Pattern EDITABLE_ROW =
            Pattern.compile("<editableTextRow\\s+id=\"([^\"]+)\"");
    private static final Pattern ALERT_TAG =
            Pattern.compile("<alert\\s[^>]*>");
    private static final Pattern NAVIGATION_BAR =
            Pattern.compile("<navigationBar\\s[^>]*>");
    private static final Pattern SECTION_FOOTER =
            Pattern.compile("<section\\s+footer=\"([^\"]*)\"");
    private static final Pattern ATTR_TITLE =
            Pattern.compile("title\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ATTR_MESSAGE =
            Pattern.compile("message\\s*=\\s*\"([^\"]*)\"");

    private ActivationChallengeCodec() {
    }

    /**
     * True when the Albert body is any buddyml page (credentials form,
     * alert or retry page). Every {@code <xmlui} payload must stay on the
     * phone side: the Watch mobileactivation daemon cannot parse it and
     * fails with "Failed to extract activation record".
     */
    static boolean isChallengePage(
            byte[] body) {
        if (body == null || body.length == 0
                || body.length > 1024 * 1024) {
            return false;
        }
        String text =
                new String(
                        body,
                        StandardCharsets.UTF_8);
        return text.stripLeading()
                .startsWith("<xmlui");
    }

    /** Classifies a buddyml page: credentials form, alert or retry page. */
    static PageInfo classify(
            byte[] body) {
        if (!isChallengePage(body)) {
            throw new IllegalArgumentException(
                    "Not a buddyml page");
        }
        String text =
                new String(
                        body,
                        StandardCharsets.UTF_8);
        Matcher alertMatcher =
                ALERT_TAG.matcher(
                        text);
        if (alertMatcher.find()) {
            String tag =
                    alertMatcher.group(
                            0);
            return new PageInfo(
                    PageKind.ALERT,
                    null,
                    attribute(
                            tag,
                            ATTR_TITLE),
                    attribute(
                            tag,
                            ATTR_MESSAGE),
                    null,
                    null,
                    null,
                    List.of());
        }
        String pageName =
                null;
        Matcher pageMatcher =
                PAGE_NAME.matcher(
                        text);
        if (pageMatcher.find()) {
            pageName =
                    pageMatcher.group(
                            1);
        }
        String maskedAccount =
                null;
        Matcher boldMatcher =
                BOLD_DIV.matcher(
                        text);
        if (boldMatcher.find()) {
            maskedAccount =
                    boldMatcher.group(
                            1).strip();
        }
        String activationInfoBase64 =
                null;
        Matcher infoMatcher =
                ACTIVATION_INFO.matcher(
                        text);
        if (infoMatcher.find()) {
            activationInfoBase64 =
                    infoMatcher.group(
                            1);
        }
        String submitUrl =
                null;
        Matcher linkMatcher =
                SUBMIT_LINK.matcher(
                        text);
        if (linkMatcher.find()) {
            String tag =
                    linkMatcher.group(
                            0);
            Matcher methodMatcher =
                    ATTR_METHOD.matcher(
                            tag);
            String method =
                    methodMatcher.find()
                            ? methodMatcher.group(
                                    1).toUpperCase(
                                            Locale.ROOT)
                            : "POST";
            Matcher urlMatcher =
                    ATTR_URL.matcher(
                            tag);
            if ("POST".equals(
                    method)
                    && urlMatcher.find()) {
                submitUrl =
                        urlMatcher.group(
                                1);
            }
        }
        List<String> fields =
                new ArrayList<>();
        Matcher rowMatcher =
                EDITABLE_ROW.matcher(
                        text);
        while (rowMatcher.find()) {
            String id =
                    rowMatcher.group(
                            1);
            if (id != null
                    && !id.isBlank()
                    && !fields.contains(
                            id)) {
                fields.add(
                        id);
            }
        }
        if (!fields.isEmpty()
                && activationInfoBase64 != null
                && submitUrl != null) {
            return new PageInfo(
                    PageKind.FORM,
                    pageName,
                    null,
                    null,
                    maskedAccount,
                    activationInfoBase64,
                    submitUrl,
                    fields);
        }
        String title =
                null;
        Matcher navMatcher =
                NAVIGATION_BAR.matcher(
                        text);
        if (navMatcher.find()) {
            title =
                    attribute(
                            navMatcher.group(
                                    0),
                            ATTR_TITLE);
        }
        String message =
                null;
        Matcher footerMatcher =
                SECTION_FOOTER.matcher(
                        text);
        if (footerMatcher.find()) {
            message =
                    footerMatcher.group(
                            1);
        }
        return new PageInfo(
                PageKind.RETRY,
                pageName,
                title,
                message,
                maskedAccount,
                activationInfoBase64,
                submitUrl,
                fields);
    }

    private static String attribute(
            String tag,
            Pattern pattern) {
        Matcher matcher =
                pattern.matcher(
                        tag);
        return matcher.find()
                ? matcher.group(
                        1)
                : null;
    }

    /** Parses a credentials-form page; throws for any other page kind. */
    static Challenge parse(
            byte[] body) {
        PageInfo info =
                classify(
                        body);
        if (info.kind != PageKind.FORM) {
            throw new IllegalArgumentException(
                    "Activation challenge page is not a credentials form");
        }
        return new Challenge(
                info.pageName == null
                        ? "form"
                        : info.pageName,
                info.maskedAccount,
                info.activationInfoBase64,
                info.submitUrl,
                info.fields);
    }

    /**
     * Builds the form-encoded resubmission body. Albert accepts exactly one
     * activation-info field: the echoed {@code activation-info-base64} from
     * {@code serverInfo}; page fields are appended under their row
     * identifiers. Callers must wipe the returned array.
     */
    static byte[] buildSubmitBody(
            Challenge challenge,
            Map<String, char[]> fieldValues) {
        if (challenge == null
                || fieldValues == null) {
            throw new IllegalArgumentException(
                    "Activation challenge submission is incomplete");
        }
        StringBuilder form =
                new StringBuilder(
                        4096);
        appendFormPair(
                form,
                "activation-info-base64",
                challenge.activationInfoBase64);
        for (String field :
                challenge.fields) {
            char[] value =
                    fieldValues.get(
                            field);
            if (value == null) {
                continue;
            }
            appendFormPair(
                    form,
                    field,
                    new String(
                            value));
        }
        return form.toString()
                .getBytes(
                        StandardCharsets.UTF_8);
    }

    /**
     * Builds the "Try Again" body: the original deviceActivation POST
     * reconstructed from the page's serverInfo echo. Live-verified: the
     * base64 decodes byte-identical to the Watch's activation-info XML, and
     * Albert accepts the single {@code activation-info} field. Callers must
     * wipe the returned array.
     */
    static byte[] buildRetryBody(
            String activationInfoBase64) {
        if (activationInfoBase64 == null
                || activationInfoBase64.isBlank()) {
            throw new IllegalArgumentException(
                    "Activation retry has no activation-info echo");
        }
        byte[] decoded;
        try {
            decoded =
                    java.util.Base64.getDecoder()
                            .decode(
                                    activationInfoBase64);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(
                    "Activation-info echo is not valid base64",
                    malformed);
        }
        String xml =
                new String(
                        decoded,
                        StandardCharsets.UTF_8);
        java.util.Arrays.fill(
                decoded,
                (byte) 0);
        StringBuilder form =
                new StringBuilder(
                        xml.length() + 64);
        appendFormPair(
                form,
                "activation-info",
                xml);
        return form.toString()
                .getBytes(
                        StandardCharsets.UTF_8);
    }

    private static void appendFormPair(
            StringBuilder form,
            String name,
            String value) {
        if (form.length() > 0) {
            form.append(
                    '&');
        }
        form.append(
                URLEncoder.encode(
                        name,
                        StandardCharsets.UTF_8));
        form.append(
                '=');
        form.append(
                URLEncoder.encode(
                        value,
                        StandardCharsets.UTF_8));
    }

    enum PageKind {
        /** Credentials form (e.g. FMIPLockChallenge): prompt the owner. */
        FORM,
        /** Alert page (e.g. "Apple Account disabled"): show, then retry. */
        ALERT,
        /** Field-less page with a Try Again button: replay activation. */
        RETRY
    }

    static final class PageInfo {
        final PageKind kind;
        final String pageName;
        final String title;
        final String message;
        final String maskedAccount;
        final String activationInfoBase64;
        final String submitUrl;
        final List<String> fields;

        private PageInfo(
                PageKind kind,
                String pageName,
                String title,
                String message,
                String maskedAccount,
                String activationInfoBase64,
                String submitUrl,
                List<String> fields) {
            this.kind =
                    kind;
            this.pageName =
                    pageName;
            this.title =
                    title;
            this.message =
                    message;
            this.maskedAccount =
                    maskedAccount;
            this.activationInfoBase64 =
                    activationInfoBase64;
            this.submitUrl =
                    submitUrl;
            this.fields =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    fields));
        }

        Challenge toFormChallenge() {
            if (kind != PageKind.FORM) {
                throw new IllegalStateException(
                        "Page is not a credentials form");
            }
            return new Challenge(
                    pageName == null
                            ? "form"
                            : pageName,
                    maskedAccount,
                    activationInfoBase64,
                    submitUrl,
                    fields);
        }
    }

    static final class Challenge {
        final String pageName;
        /** Server-masked account hint (e.g. {@code l•••••@gmail.com}); may be null. */
        final String maskedAccount;
        final String activationInfoBase64;
        final String submitUrl;
        final List<String> fields;

        private Challenge(
                String pageName,
                String maskedAccount,
                String activationInfoBase64,
                String submitUrl,
                List<String> fields) {
            this.pageName =
                    pageName;
            this.maskedAccount =
                    maskedAccount;
            this.activationInfoBase64 =
                    activationInfoBase64;
            this.submitUrl =
                    submitUrl;
            this.fields =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    fields));
        }
    }
}
