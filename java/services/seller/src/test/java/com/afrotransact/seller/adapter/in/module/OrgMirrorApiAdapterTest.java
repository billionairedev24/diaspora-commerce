package com.afrotransact.seller.adapter.in.module;

import java.util.List;

import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.seller.api.OrgMirrorApi;
import com.afrotransact.seller.application.OrgMirrorService;
import org.junit.jupiter.api.Test;

/** S6 for the organisation mirror: {@code sellers:directory} is required, a caller without it is refused with 403. */
class OrgMirrorApiAdapterTest {

    private final OrgMirrorApi api = new OrgMirrorApiAdapter(InProcessKit.guarded(OrgMirrorService.class, "sellers:directory"),
            InProcessKit.ACTORS);

    @Test
    void pageWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("identity"), () -> api.page(0, 10, List.of("APPROVED")));
    }

    @Test
    void oneWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("identity"), () -> api.one("org"));
    }
}
