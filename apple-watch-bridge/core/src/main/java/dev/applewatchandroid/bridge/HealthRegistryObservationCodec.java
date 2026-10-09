package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

/** Read-only subset of an authenticated full NanoRegistry snapshot. Never adopts a Health identity. */
final class HealthRegistryObservationCodec {
    static final String PREFIX="BRIDGE_HEALTH_REGISTRY_V1:";
    static final int MAX_FRAME=2048;
    record Observation(UUID pair,UUID epoch,UUID local,UUID peer,String product,String build,UUID nativePairing) {
        Observation {
            if(pair==null || epoch==null || local==null || peer==null || local.equals(peer)
                    || product==null || !product.matches("Watch[0-9]{1,3},[0-9]{1,3}")
                    || build!=null && !build.matches("[0-9]{1,3}[A-Z][0-9]{1,6}[a-z]?")) throw new IllegalArgumentException();
        }
        void requireContext(UUID owner,UUID currentEpoch,UUID sender,UUID receiver) {
            if(!pair.equals(owner) || !epoch.equals(currentEpoch) || !local.equals(sender) || !peer.equals(receiver)) {
                throw new IllegalArgumentException("Foreign registry observation");
            }
        }
    }
    static Observation fromSnapshot(UUID pair,UUID epoch,UUID local,UUID peer,NanoRegistryPropertyCodec.PropertiesChanged snapshot) {
        if(!snapshot.thisIsAllOfThem)throw new IllegalArgumentException("Not a full registry snapshot");
        String product=null,build=null;UUID pairing=null;var seen=new java.util.HashSet<String>();
        for(var property:snapshot.properties) {
            if(!java.util.Set.of("productType","systemBuildVersion","pairingID").contains(property.name))continue;
            if(!seen.add(property.name))throw new IllegalArgumentException("Duplicate registry identity property");
            var value=property.value;
            if(value==null || value.isError || value.hasIsSet && !value.isSet) {
                if("productType".equals(property.name))throw new IllegalArgumentException("Unavailable registry productType");
                continue; // Optional properties absent in the full MiniStore are not invented.
            }
            switch(property.name) {
                case "productType" -> product=text(value);
                case "systemBuildVersion" -> build=text(value);
                case "pairingID" -> {
                    if(value.uuidValue==null || value.uuidValue.length!=16)throw new IllegalArgumentException("Invalid registry UUID");
                    var bytes=ByteBuffer.wrap(value.uuidValue);pairing=new UUID(bytes.getLong(),bytes.getLong());
                }
            }
        }
        return new Observation(pair,epoch,local,peer,product,build,pairing);
    }
    static byte[] encode(Observation value) {
        var map=new java.util.LinkedHashMap<String,Object>();
        map.put("v",1L);map.put("pair",value.pair.toString());map.put("epoch",value.epoch.toString());
        map.put("local",value.local.toString());map.put("peer",value.peer.toString());
        map.put("product",value.product);if(value.build!=null)map.put("build",value.build);
        if(value.nativePairing!=null)map.put("registryPair",value.nativePairing.toString());
        byte[] frame=AppleBinaryPropertyList.encode(map);
        if(frame.length>MAX_FRAME)throw new IllegalArgumentException("Registry observation limit");return frame;
    }
    static Observation decode(byte[] frame) {
        if(frame==null || frame.length>MAX_FRAME)throw new IllegalArgumentException("Registry observation limit");
        Object decoded=AppleBinaryPropertyList.decode(frame);
        try {
            if(!(decoded instanceof Map<?,?> map) || map.size()!=6+(map.containsKey("registryPair")?1:0)+(map.containsKey("build")?1:0)
                    || !Long.valueOf(1).equals(map.get("v")) || !(map.get("product") instanceof String product)
                    || map.containsKey("build") && !(map.get("build") instanceof String))throw new IllegalArgumentException("Registry observation fields");
            return new Observation(uuid(map.get("pair")),uuid(map.get("epoch")),uuid(map.get("local")),uuid(map.get("peer")),
                    product,(String)map.get("build"),map.containsKey("registryPair")?uuid(map.get("registryPair")):null);
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
    private static String text(NanoRegistryPropertyCodec.PropertyValue value) {
        if(value.stringValue!=null)return value.stringValue;
        if(value.dataValue==null || value.dataValue.length==0 || value.dataValue.length>64)throw new IllegalArgumentException("Invalid registry text representation");
        for(byte b:value.dataValue)if(b<0x21 || b>0x7e)throw new IllegalArgumentException("Registry text is not ASCII");
        return new String(value.dataValue,java.nio.charset.StandardCharsets.US_ASCII);
    }
    static String describe(NanoRegistryPropertyCodec.PropertiesChanged snapshot) {
        StringBuilder report=new StringBuilder("full="+snapshot.thisIsAllOfThem);
        for(String name:java.util.List.of("productType","systemBuildVersion","pairingID")) {
            var matches=snapshot.properties.stream().filter(p->name.equals(p.name)).collect(java.util.stream.Collectors.toList());
            report.append(' ').append(name).append('=');
            if(matches.isEmpty())report.append("ABSENT");
            else if(matches.size()!=1)report.append("DUPLICATE");
            else {
                var value=matches.get(0).value;
                if(value==null)report.append("UNSET");
                else if(value.isError || value.hasIsSet && !value.isSet)report.append("UNAVAILABLE");
                else if(value.stringValue!=null)report.append("STRING");
                else if(value.dataValue!=null)report.append("DATA:").append(value.dataValue.length);
                else if(value.uuidValue!=null)report.append("UUID:").append(value.uuidValue.length);
                else report.append("OTHER");
            }
        }
        return report.toString();
    }
    private static UUID uuid(Object value) {
        if(!(value instanceof String text))throw new IllegalArgumentException("Missing UUID");
        UUID uuid=UUID.fromString(text);if(!uuid.toString().equals(text))throw new IllegalArgumentException("Noncanonical UUID");return uuid;
    }
}
