#import <Foundation/Foundation.h>
#import <CoreBluetooth/CoreBluetooth.h>
#import "pairing_pipe.h"

typedef void (*AWEventCallback)(const char *);
@interface AWCentral : NSObject <CBCentralManagerDelegate>
@property(nonatomic) AWEventCallback callback;
@property(nonatomic, strong) CBCentralManager *central;
@property(nonatomic, strong) NSMutableDictionary<NSString *, CBPeripheral *> *devices;
@property(nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *linkEpochs;
@property(nonatomic) uint64_t scanEpoch;
@property(nonatomic) BOOL scanning;
@property(nonatomic, strong) AWPairingPipe *pairing;
- (void)emit:(NSDictionary *)event;
- (void)resumeScan;
@end

@implementation AWCentral
- (instancetype)init {
    if ((self = [super init])) {
        _devices = [NSMutableDictionary new];
        _linkEpochs = [NSMutableDictionary new];
    }
    return self;
}
- (void)emit:(NSDictionary *)event {
    if (!self.callback) return;
    NSData *data = [NSJSONSerialization dataWithJSONObject:event options:0 error:nil];
    NSString *json = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    if (json) self.callback(json.UTF8String);
}
- (void)resumeScan {
    if (self.scanning && self.central.state == CBManagerStatePoweredOn)
        [self.central scanForPeripheralsWithServices:nil options:@{CBCentralManagerScanOptionAllowDuplicatesKey: @YES}];
}
- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
    NSString *state = @"UNKNOWN";
    switch (central.state) {
        case CBManagerStatePoweredOn: state = @"POWERED_ON"; break;
        case CBManagerStatePoweredOff: state = @"POWERED_OFF"; break;
        case CBManagerStateUnauthorized: state = @"UNAUTHORIZED"; break;
        case CBManagerStateUnsupported: state = @"UNSUPPORTED"; break;
        case CBManagerStateResetting: state = @"RESETTING"; break;
        default: break;
    }
    [self emit:@{@"event": @"adapter", @"state": state}];
    if (central.state != CBManagerStatePoweredOn) {
        [self.pairing close]; self.pairing = nil;
        for (NSString *identifier in self.linkEpochs) {
            CBPeripheral *device = self.devices[identifier];
            if (device.state != CBPeripheralStateDisconnected) [central cancelPeripheralConnection:device];
        }
    }
    [self resumeScan];
}
- (void)centralManager:(CBCentralManager *)central didDiscoverPeripheral:(CBPeripheral *)peripheral
    advertisementData:(NSDictionary<NSString *, id> *)advertisementData RSSI:(NSNumber *)RSSI {
    if (!self.scanning) return;
    NSDictionary *services = advertisementData[CBAdvertisementDataServiceDataKey];
    NSData *setup = services[[CBUUID UUIDWithString:@"FE25"]];
    NSData *manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey];
    const uint8_t *bytes = manufacturer.bytes;
    BOOL apple = manufacturer.length >= 2 && bytes[0] == 0x4c && bytes[1] == 0;
    if (!setup && !apple) return;
    NSString *identifier = peripheral.identifier.UUIDString;
    if (self.devices.count >= 128 && !self.devices[identifier]) return;
    self.devices[identifier] = peripheral;
    NSMutableArray *payload = [NSMutableArray new];
    if (setup.length <= 64) {
        const uint8_t *serviceBytes = setup.bytes;
        for (NSUInteger i = 0; i < setup.length; i++) [payload addObject:@(serviceBytes[i])];
    }
    NSString *name = advertisementData[CBAdvertisementDataLocalNameKey] ?: peripheral.name ?: @"";
    [self emit:@{@"event": @"discovered", @"epoch": @(self.scanEpoch), @"id": identifier,
        @"name": [name substringToIndex:MIN(name.length, 96)], @"rssi": RSSI,
        @"apple": @(apple), @"setup": payload}];
}
- (void)centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral {
    NSString *identifier = peripheral.identifier.UUIDString;
    NSNumber *epoch = self.linkEpochs[identifier];
    if (epoch) [self emit:@{@"event": @"connected", @"epoch": epoch, @"id": identifier}];
}
- (void)centralManager:(CBCentralManager *)central didFailToConnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error {
    NSString *identifier = peripheral.identifier.UUIDString;
    NSNumber *epoch = self.linkEpochs[identifier];
    if (epoch) {
        [self.pairing close]; self.pairing = nil;
        [self emit:@{@"event": @"failed", @"epoch": epoch,
            @"message": [NSString stringWithFormat:@"Bluetooth connection failed (%ld)", (long)error.code]}];
        [self emit:@{@"event": @"disconnected", @"epoch": epoch, @"id": identifier}];
        [self.linkEpochs removeObjectForKey:identifier];
    }
}
- (void)centralManager:(CBCentralManager *)central didDisconnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error {
    NSString *identifier = peripheral.identifier.UUIDString;
    NSNumber *epoch = self.linkEpochs[identifier];
    if (epoch) { [self.pairing close]; self.pairing = nil; }
    if (epoch) [self emit:@{@"event": @"disconnected", @"epoch": epoch, @"id": identifier}];
    [self.linkEpochs removeObjectForKey:identifier];
}
@end

static AWCentral *owner;
void aw_macos_initialize(AWEventCallback callback) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!owner) owner = [AWCentral new];
        owner.callback = callback;
        // Readiness is initialized on explicit Scan, avoiding an automatic permission prompt.
    });
}
void aw_macos_scan(uint64_t epoch) {
    dispatch_async(dispatch_get_main_queue(), ^{
        owner.scanEpoch = epoch; owner.scanning = YES;
        [owner.devices removeAllObjects];
        if (!owner.central) owner.central = [[CBCentralManager alloc] initWithDelegate:owner queue:dispatch_get_main_queue()];
        else [owner centralManagerDidUpdateState:owner.central];
    });
}
void aw_macos_stop_scan(void) {
    dispatch_async(dispatch_get_main_queue(), ^{ owner.scanning = NO; [owner.central stopScan]; });
}
void aw_macos_connect(uint64_t epoch, const char *identifier) {
    NSString *copied = [NSString stringWithUTF8String:identifier];
    dispatch_async(dispatch_get_main_queue(), ^{
        CBPeripheral *device = owner.devices[copied];
        if (!device || owner.central.state != CBManagerStatePoweredOn) {
            [owner emit:@{@"event": @"failed", @"epoch": @(epoch), @"message": @"Bluetooth device is no longer available"}];
            [owner emit:@{@"event": @"disconnected", @"epoch": @(epoch), @"id": copied}];
            return;
        }
        owner.linkEpochs[copied] = @(epoch);
        [owner.pairing close];
        owner.pairing = [[AWPairingPipe alloc] initWithPeer:copied epoch:epoch emit:^(NSDictionary *event) {
            if ([owner.linkEpochs[copied] unsignedLongLongValue] != epoch) return;
            [owner emit:event];
            if ([event[@"event"] isEqualToString:@"failed"]) {
                [owner.central cancelPeripheralConnection:device];
                if (device.state == CBPeripheralStateDisconnected) {
                    [owner emit:@{@"event": @"disconnected", @"epoch": @(epoch), @"id": copied}];
                    [owner.linkEpochs removeObjectForKey:copied];
                }
            }
        } ready:^{
            if ([owner.linkEpochs[copied] unsignedLongLongValue] == epoch)
                [owner.central connectPeripheral:device options:nil];
        }];
        [owner.pairing start];
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 20 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
            if ([owner.linkEpochs[copied] unsignedLongLongValue] == epoch && device.state == CBPeripheralStateConnecting) {
                [owner emit:@{@"event": @"failed", @"epoch": @(epoch), @"message": @"Bluetooth connection timed out"}];
                [owner.central cancelPeripheralConnection:device];
            }
        });
    });
}
void aw_macos_disconnect(uint64_t epoch, const char *identifier) {
    NSString *copied = [NSString stringWithUTF8String:identifier];
    dispatch_async(dispatch_get_main_queue(), ^{
        if ([owner.linkEpochs[copied] unsignedLongLongValue] != epoch) return;
        CBPeripheral *device = owner.devices[copied];
        if ([owner.linkEpochs[copied] unsignedLongLongValue] == epoch) {
            [owner.pairing close]; owner.pairing = nil;
        }
        if (device && device.state != CBPeripheralStateDisconnected) [owner.central cancelPeripheralConnection:device];
        else {
            [owner emit:@{@"event": @"disconnected", @"epoch": @(epoch), @"id": copied}];
            [owner.linkEpochs removeObjectForKey:copied];
        }
    });
}

bool aw_macos_pairing_bootstrap_allowed(void) { return AWMacPairingBootstrapAllowed(); }

void aw_macos_pairing_send(uint64_t epoch, const char *identifier, const uint8_t *bytes, size_t length) {
    if (!identifier || !bytes || !length || length > 65536) return;
    NSString *copied = [NSString stringWithUTF8String:identifier];
    NSData *data = [NSData dataWithBytes:bytes length:length];
    dispatch_async(dispatch_get_main_queue(), ^{
        if ([owner.linkEpochs[copied] unsignedLongLongValue] == epoch)
            [owner.pairing send:data];
    });
}
