package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public final class HealthOutboundIpcCodecTest {
    private static HealthOutboundIpcCodec.Header header() {
        return new HealthOutboundIpcCodec.Header(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),60001);
    }
    private static byte[] cipher() { return AppleBinaryPropertyList.encode(Map.of("ekd",new byte[]{2,0,1,0},"sed",new byte[16])); }
    @Test public void ciphertextIsOwnedAndContextRemainsBoundThroughPrivateIpc() {
        var header=header();byte[] original=cipher(),input=original.clone();
        try(var request=new HealthOutboundIpcCodec.Request(header,input)) {
            input[0]=0;assertArrayEquals(original,request.encrypted());
            try(var decoded=HealthOutboundIpcCodec.decode(HealthOutboundIpcCodec.encode(request))) {
                assertEquals(header,decoded.header);assertArrayEquals(original,decoded.encrypted());
                byte[] copy=decoded.encrypted();copy[0]=0;assertArrayEquals(original,decoded.encrypted());
                decoded.header.requireContext(header.pair(),header.local(),header.peer(),header.epoch(),1);
            }
            request.close();assertThrows(IllegalStateException.class,request::encrypted);
            assertThrows(IllegalStateException.class,()->HealthOutboundIpcCodec.encode(request));
        }
    }
    @Test public void foreignIdentitiesStaleEpochAndOverlongDeadlineFailAtActualSend() {
        var h=header();
        assertThrows(IllegalArgumentException.class,()->h.requireContext(UUID.randomUUID(),h.local(),h.peer(),h.epoch(),1));
        assertThrows(IllegalArgumentException.class,()->h.requireContext(h.pair(),UUID.randomUUID(),h.peer(),h.epoch(),1));
        assertThrows(IllegalArgumentException.class,()->h.requireContext(h.pair(),h.local(),UUID.randomUUID(),h.epoch(),1));
        assertThrows(IllegalArgumentException.class,()->h.requireContext(h.pair(),h.local(),h.peer(),UUID.randomUUID(),1));
        assertThrows(IllegalArgumentException.class,()->h.requireContext(h.pair(),h.local(),h.peer(),h.epoch(),60001));
        assertThrows(IllegalArgumentException.class,()->h.requireContext(h.pair(),h.local(),h.peer(),h.epoch(),0));
        assertThrows(IllegalArgumentException.class,()->h.requireContext(h.pair(),h.local(),h.peer(),h.epoch(),Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class,()->new HealthOutboundIpcCodec.Header(h.pair(),h.local(),h.peer(),h.epoch(),UUID.fromString("00000000-0000-1000-8000-000000000000"),100));
    }
    @Test public void plaintextMalformedCiphertextAndAdditionalIpcFieldsAreRefused() {
        var h=header();
        for(byte[] bytes:new byte[][]{new byte[]{1,0,0},AppleBinaryPropertyList.encode(Map.of("sed",new byte[16])),
                AppleBinaryPropertyList.encode(Map.of("ekd",new byte[]{2,0,1,0},"sed",new byte[15]))}) {
            assertThrows(IllegalArgumentException.class,()->new HealthOutboundIpcCodec.Request(h,bytes));
        }
        Map<String,Object> map=new java.util.LinkedHashMap<>(Map.of("v",1L,"pair",h.pair().toString(),"local",h.local().toString(),
                "peer",h.peer().toString(),"epoch",h.epoch().toString(),"id",h.message().toString(),"deadline",h.deadline(),"encrypted",cipher()));
        map.put("plaintext",new byte[]{1,0,0});assertThrows(IllegalArgumentException.class,()->HealthOutboundIpcCodec.decode(AppleBinaryPropertyList.encode(map)));
        map.remove("plaintext");map.remove("epoch");assertThrows(IllegalArgumentException.class,()->HealthOutboundIpcCodec.decode(AppleBinaryPropertyList.encode(map)));
        assertThrows(IllegalArgumentException.class,()->HealthOutboundIpcCodec.decode(new byte[HealthOutboundIpcCodec.MAX_FRAME+1]));
    }
    @Test public void registryObservationKeepsMissingIdentityAbsentAndRejectsForeignContext() {
        UUID pair=UUID.randomUUID(),epoch=UUID.randomUUID(),local=UUID.randomUUID(),peer=UUID.randomUUID();
        for(UUID nativeId:java.util.Arrays.asList(null,UUID.randomUUID())) {
            var original=new HealthRegistryObservationCodec.Observation(pair,epoch,local,peer,"Watch7,5","23S303",nativeId);
            var decoded=HealthRegistryObservationCodec.decode(HealthRegistryObservationCodec.encode(original));assertEquals(original,decoded);
            decoded.requireContext(pair,epoch,local,peer);
            assertThrows(IllegalArgumentException.class,()->decoded.requireContext(pair,UUID.randomUUID(),local,peer));
            assertThrows(IllegalArgumentException.class,()->decoded.requireContext(UUID.randomUUID(),epoch,local,peer));
            assertThrows(IllegalArgumentException.class,()->decoded.requireContext(pair,epoch,UUID.randomUUID(),peer));
            assertThrows(IllegalArgumentException.class,()->decoded.requireContext(pair,epoch,local,UUID.randomUUID()));
        }
        assertThrows(IllegalArgumentException.class,()->new HealthRegistryObservationCodec.Observation(pair,epoch,local,local,"Watch7,5","23S303",null));
        assertThrows(IllegalArgumentException.class,()->HealthRegistryObservationCodec.decode(new byte[2049]));
    }
}
