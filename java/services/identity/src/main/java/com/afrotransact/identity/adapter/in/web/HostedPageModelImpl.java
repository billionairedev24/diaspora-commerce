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
        model.addAttribute("brand", brand(ctx.app(), mode));
        model.addAttribute("links", Map.of("home", ctx.appUrl(), "support", settings.supportUrl(),
                "terms", settings.termsUrl(), "privacy", settings.privacyUrl(), "sms", settings.smsTermsUrl()));
        model.addAttribute("smsProgram", settings.smsProgram());
        model.addAttribute("providers", providers.enabledProviders().stream()
                .map(p -> new Provider(p.id(), p.displayName())).toList());
        model.addAttribute("selfSignUp", ctx.app().selfSignUp());
        return ctx;
    }

    private HostedBrand brand(HostedApp app, String mode) {
        Locale locale = LocaleContextHolder.getLocale();
        String p = "auth." + app.key() + ".";
        List<HostedBrand.Point> points = new ArrayList<>();
        for (int i = 1; i <= POINTS; i++) {
            points.add(new HostedBrand.Point(text(p + "point" + i + ".icon", locale),
                    text(p + "point" + i + ".title", locale), text(p + "point" + i + ".text", locale)));
        }
        String badge = app == HostedApp.SHOP ? null : text(p + "badge", locale);
        return new HostedBrand(app.key(), badge, text(p + mode + ".title", locale), text(p + mode + ".tag", locale),
                text(p + mode + ".head", locale), text(p + mode + ".sub", locale), List.copyOf(points));
    }

    private String text(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }

    /** A social sign-in option offered on the page (configured and enabled by an administrator). */
    record Provider(String id, String name) {
    }
}
