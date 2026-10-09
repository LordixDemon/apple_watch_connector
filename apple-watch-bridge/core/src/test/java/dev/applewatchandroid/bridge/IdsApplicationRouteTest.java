package dev.applewatchandroid.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class IdsApplicationRouteTest {

    @Test
    public void testApplicationRoutes() {
        IdsApplicationRoute bulletinRoute =
                IdsApplicationRoute.forTopic(IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE);
        assertNotNull(bulletinRoute);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_C, bulletinRoute.idsProtectionClass);

        IdsApplicationRoute findMyRoute =
                IdsApplicationRoute.forTopic(IdsApplicationRoute.FIND_MY_LOCAL_SERVICE);
        assertNotNull(findMyRoute);
        assertEquals("com.apple.private.alloy.findmylocaldevice", findMyRoute.topic);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_D, findMyRoute.idsProtectionClass);

        IdsApplicationRoute healthRoute =
                IdsApplicationRoute.forTopic(IdsApplicationRoute.HEALTH_SYNC_SERVICE);
        assertNotNull(healthRoute);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_C, healthRoute.idsProtectionClass);

        IdsApplicationRoute telephonyRoute =
                IdsApplicationRoute.forTopic(IdsApplicationRoute.TELEPHONY_SERVICE);
        assertNotNull(telephonyRoute);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_C, telephonyRoute.idsProtectionClass);

        IdsApplicationRoute prefRoute =
                IdsApplicationRoute.forTopic(IdsApplicationRoute.PREFERENCE_SYNC_SERVICE);
        assertNotNull(prefRoute);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_D, prefRoute.idsProtectionClass);

        IdsApplicationRoute pairedSyncRoute =
                IdsApplicationRoute.forTopic(IdsApplicationRoute.PAIRED_SYNC_SERVICE);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_C,
                pairedSyncRoute.idsProtectionClass);

        IdsApplicationRoute bulletinSettingsRoute =
                IdsApplicationRoute.forTopic(IdsApplicationRoute.BULLETIN_SETTINGS_SERVICE);
        assertNotNull(bulletinSettingsRoute);
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_D, bulletinSettingsRoute.idsProtectionClass);
    }
}
