package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class NativeHealthRegistryBindingTest {
    private static final UUID PAIR=UUID.randomUUID(),EPOCH=UUID.randomUUID(),LOCAL=UUID.randomUUID(),PEER=UUID.randomUUID(),REGISTRY=UUID.randomUUID();
    private static HealthRegistryObservationCodec.Observation observation(String build,UUID remote) {
        return new HealthRegistryObservationCodec.Observation(PAIR,EPOCH,LOCAL,PEER,"Watch7,5",build,remote);
    }
    private static NativeHealthRegistryBinding.Binding bind(HealthRegistryObservationCodec.Observation observed,UUID registry,String build) {
        return NativeHealthRegistryBinding.bind(PAIR,EPOCH,LOCAL,PEER,observed,registry,build);
    }
    @Test public void localEntryUsesReservedNrUuidAndNeverPeerOrApplicationOwnerUuid() {
        UUID peerRegistry=UUID.randomUUID();var binding=bind(observation(null,peerRegistry),REGISTRY,"23S303");
        assertEquals(REGISTRY,binding.registry());assertNotEquals(PAIR,binding.registry());assertNotEquals(peerRegistry,binding.registry());
        assertEquals("Watch7,5",binding.product());assertEquals("23S303",binding.build());
        assertEquals(binding,bind(observation("23S303",null),REGISTRY,"23S303"));
    }
    @Test public void conflictingBuildUnknownFirmwareUnknownModelAndMissingReservedIdentityRefuse() {
        assertThrows(IllegalArgumentException.class,()->bind(observation("23S304",null),REGISTRY,"23S303"));
        assertThrows(IllegalArgumentException.class,()->bind(observation(null,null),REGISTRY,null));
        assertThrows(IllegalArgumentException.class,()->bind(observation(null,null),REGISTRY,"23S304"));
        assertThrows(IllegalArgumentException.class,()->bind(observation(null,null),null,"23S303"));
        assertThrows(IllegalArgumentException.class,()->bind(observation(null,null),new UUID(0,0),"23S303"));
        var unknown=new HealthRegistryObservationCodec.Observation(PAIR,EPOCH,LOCAL,PEER,"Watch7,4",null,null);
        assertThrows(IllegalArgumentException.class,()->bind(unknown,REGISTRY,"23S303"));
    }
    @Test public void staleForeignRegistryEvidenceNeverCreatesALocalBinding() {
        for(var foreign:List.of(new HealthRegistryObservationCodec.Observation(UUID.randomUUID(),EPOCH,LOCAL,PEER,"Watch7,5",null,null),
                new HealthRegistryObservationCodec.Observation(PAIR,UUID.randomUUID(),LOCAL,PEER,"Watch7,5",null,null),
                new HealthRegistryObservationCodec.Observation(PAIR,EPOCH,UUID.randomUUID(),PEER,"Watch7,5",null,null),
                new HealthRegistryObservationCodec.Observation(PAIR,EPOCH,LOCAL,UUID.randomUUID(),"Watch7,5",null,null))) {
            assertThrows(IllegalArgumentException.class,()->bind(foreign,REGISTRY,"23S303"));
        }
    }
    private static NanoRegistryPropertyCodec.Property property(String name,NanoRegistryPropertyCodec.PropertyValue value) {
        return new NanoRegistryPropertyCodec.Property(name,value);
    }
    private static HealthRegistryObservationCodec.Observation decode(boolean full,List<NanoRegistryPropertyCodec.Property> properties) {
        var snapshot=new NanoRegistryPropertyCodec.PropertiesChanged(full,properties,null);
        byte[] wire=NanoRegistryPropertyCodec.encodePropertiesChanged(snapshot);var received=NanoRegistryPropertyCodec.decodePropertiesChanged(wire);
        try { return HealthRegistryObservationCodec.fromSnapshot(PAIR,EPOCH,LOCAL,PEER,received); }
        finally { snapshot.destroy();received.destroy();java.util.Arrays.fill(wire,(byte)0); }
    }
    @Test public void fullMiniStoreWithAbsentBuildAndPairingIdRemainsExplicitlyPartial() {
        var observed=decode(true,List.of(property("productType",NanoRegistryPropertyCodec.PropertyValue.string("Watch7,5"))));
        assertNull(observed.build());assertNull(observed.nativePairing());
        assertEquals(observed,HealthRegistryObservationCodec.decode(HealthRegistryObservationCodec.encode(observed)));
        assertEquals(REGISTRY,bind(observed,REGISTRY,"23S303").registry());
    }
    @Test public void asciiDataRepresentationIsExactAndDoesNotTrimOrDecodeArchiveBytes() {
        var observed=decode(true,List.of(property("productType",NanoRegistryPropertyCodec.PropertyValue.data("Watch7,5".getBytes(java.nio.charset.StandardCharsets.US_ASCII))),
                property("systemBuildVersion",NanoRegistryPropertyCodec.PropertyValue.data("23S303".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))));
        assertEquals("Watch7,5",observed.product());assertEquals("23S303",observed.build());
        for(byte[] invalid:new byte[][]{" Watch7,5".getBytes(),"Watch7,5\u0000".getBytes(),new byte[]{(byte)0xc0},new byte[65]}) {
            assertThrows(IllegalArgumentException.class,()->decode(true,List.of(property("productType",NanoRegistryPropertyCodec.PropertyValue.data(invalid)))));
        }
    }
    @Test public void absentUnavailableProductIncrementalAndDuplicateSelectedPropertiesRefuse() {
        assertThrows(IllegalArgumentException.class,()->decode(true,List.of(property("systemBuildVersion",NanoRegistryPropertyCodec.PropertyValue.string("23S303")))));
        assertThrows(IllegalArgumentException.class,()->decode(true,List.of(property("productType",null))));
        assertThrows(IllegalArgumentException.class,()->decode(false,List.of(property("productType",NanoRegistryPropertyCodec.PropertyValue.string("Watch7,5")))));
        assertThrows(IllegalArgumentException.class,()->decode(true,List.of(property("productType",NanoRegistryPropertyCodec.PropertyValue.string("Watch7,5")),
                property("productType",NanoRegistryPropertyCodec.PropertyValue.string("Watch7,4")))));
    }
    @Test public void diagnosticsExposeOnlySelectedPropertyPresenceAndRepresentation() {
        var snapshot=new NanoRegistryPropertyCodec.PropertiesChanged(true,List.of(property("productType",NanoRegistryPropertyCodec.PropertyValue.string("Watch7,5")),
                property("systemBuildVersion",null),property("serialNumber",NanoRegistryPropertyCodec.PropertyValue.string("PRIVATE-SERIAL"))),null);
        try { assertEquals("full=true productType=STRING systemBuildVersion=UNSET pairingID=ABSENT",HealthRegistryObservationCodec.describe(snapshot)); }
        finally { snapshot.destroy(); }
    }
    @Test public void registryObservationIpcNeverAcceptsUnknownOrNonStringBuild() {
        byte[] wire=HealthRegistryObservationCodec.encode(observation(null,null));
        java.util.Map<Object,Object> map=new java.util.LinkedHashMap<>((java.util.Map<?,?>)AppleBinaryPropertyList.decode(wire));
        map.put("build",23L);assertThrows(IllegalArgumentException.class,()->HealthRegistryObservationCodec.decode(AppleBinaryPropertyList.encode(map)));
        map.remove("build");map.put("unknown",true);assertThrows(IllegalArgumentException.class,()->HealthRegistryObservationCodec.decode(AppleBinaryPropertyList.encode(map)));
    }
}
