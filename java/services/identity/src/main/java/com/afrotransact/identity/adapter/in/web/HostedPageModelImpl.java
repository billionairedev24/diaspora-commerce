package com.afrotransact.identity.adapter.in.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.identity.application.IdentityProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
class HostedPageModelImpl implements HostedPageModel {

    private static final int POINTS = 3;

    private final HostedPageContextResolver contexts;
    private final IdentityProviderService providers;
    private final MessageSource messages;
    private final LoginPageSettings settings;

    HostedPageModelImpl(HostedPageContextResolver contexts, IdentityProviderService providers,
                        MessageSource messages, LoginPageSettings settings) {
        this.contexts = contexts;
        this.providers = providers;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public HostedPageContext apply(Model model, HttpServletRequest request, HttpServletResponse response,
                                   String mode) {
        HostedPageContext ctx = contexts.resolve(request, response);
        model.addAttribute("ctx", ctx);
        model.addAttribute("brand", brand(ctx.app(), ctx.intent(), mode));
        model.addAttribute("links", Map.of("home", ctx.appUrl(), "support", settings.supportUrl(),
                "terms", settings.termsUrl(), "privacy", settings.privacyUrl(), "sms", settings.smsTermsUrl()));
        model.addAttribute("smsProgram", settings.smsProgram());
        model.addAttribute("providers", providers.enabledProviders().stream()
                .map(p -> new Provider(p.id(), p.displayName())).toList());
        model.addAttribute("selfSignUp", ctx.app().selfSignUp());
        return ctx;
    }

    /**
     * Copy under {@code auth.<app>.*}; a Seller Center entry point ({@code intent}) overrides the panel and heading
     * with {@code auth.<app>.<intent>.<mode>.*} where those keys exist.
     */
    private HostedBrand brand(HostedApp app, String intent, String mode) {
        Locale locale = LocaleContextHolder.getLocale();
        String p = "auth." + app.key() + ".";
        List<HostedBrand.Point> points = new ArrayList<>();
        for (int i = 1; i <= POINTS; i++) {
            points.add(new HostedBrand.Point(text(p + "point" + i + ".icon", locale),
                    text(p + "point" + i + ".title", locale), text(p + "point" + i + ".text", locale)));
        }
        String badge = app == HostedApp.SHOP || app == HostedApp.NEUTRAL ? null : text(p + "badge", locale);
        String specific = intent == null ? null : p + intent + "." + mode + ".";
        return new HostedBrand(app.key(), badge, copy(specific, p + mode + ".", "title", locale),
                copy(specific, p + mode + ".", "tag", locale), copy(specific, p + mode + ".", "head", locale),
                copy(specific, p + mode + ".", "sub", locale), List.copyOf(points),
                messages.getMessage(p + "toSignin", null, text("auth.toSignin", locale), locale));
    }

    /** The intent-specific message when there is one, else the app's. */
    private String copy(String specific, String general, String field, Locale locale) {
        String fallback = text(general + field, locale);
        return specific == null ? fallback : messages.getMessage(specific + field, null, fallback, locale);
    }

    private String text(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }

    /** A social sign-in option offered on the page (configured and enabled by an administrator). */
    record Provider(String id, String name) {
    }
}
