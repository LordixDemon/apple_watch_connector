#import "pairing_pipe.h"
#import <CoreBluetooth/CoreBluetooth.h>
#import <Security/SecTask.h>
#include <dlfcn.h>

// Private API, checked at runtime. bluetoothd owns BT_CL and ERTM; these streams
// contain the uIKE bytes, not ACL/L2CAP packets. No raw HCI/PSM substitution.
@interface CBManager (AWScalablePipe)
- (instancetype)initWithDelegate:(id)delegate queue:(dispatch_queue_t)queue;
- (void)registerEndpoint:(NSString *)name type:(NSInteger)type
               priority:(NSInteger)priority options:(NSDictionary *)options;
- (void)unregisterEndpoint:(NSString *)name;
@end
@protocol AWNativePipe
- (NSString *)name;
- (CBPeer *)peer;
- (NSInputStream *)input;
- (NSOutputStream *)output;
@end

static NSString *const endpoint = @"com.apple.terminusPairing";
BOOL AWMacPairingBootstrapAllowed(void) {
    // macOS 15 bluetoothd forcibly replaces transport/encryption options for
    // non-internal clients. Checking our own OS-validated grant avoids claiming
    // that a successful endpoint ACK means an unencrypted bootstrap is usable.
    SecTaskRef task = SecTaskCreateFromSelf(kCFAllocatorDefault);
    if (!task) return NO;
    CFTypeRef value = SecTaskCopyValueForEntitlement(task, CFSTR("com.apple.bluetooth.internal"), NULL);
    BOOL allowed = value && CFGetTypeID(value) == CFBooleanGetTypeID() && CFBooleanGetValue(value);
    if (value) CFRelease(value);
    CFRelease(task);
    return allowed;
}
static NSString *option(const char *name) {
    NSString * __unsafe_unretained const *value =
        (NSString * __unsafe_unretained const *)dlsym(RTLD_DEFAULT, name);
    return value ? *value : nil;
}

@interface AWPairingPipe () <NSStreamDelegate>
@property(nonatomic, copy) NSString *peerId;
@property(nonatomic) uint64_t epoch;
@property(nonatomic, copy) void (^emit)(NSDictionary *);
@property(nonatomic, copy) void (^ready)(void);
@property(nonatomic, strong) CBManager *manager;
@property(nonatomic, strong) id<AWNativePipe> pipe;
@property(nonatomic, strong) NSInputStream *input;
@property(nonatomic, strong) NSOutputStream *output;
@property(nonatomic, strong) NSMutableData *pending;
@property(nonatomic) BOOL requested;
@property(nonatomic) BOOL registered;
@property(nonatomic) BOOL closed;
@property(nonatomic) BOOL inputOpen;
@property(nonatomic) BOOL outputOpen;
@property(nonatomic) BOOL opened;
@end

@implementation AWPairingPipe
- (instancetype)initWithPeer:(NSString *)peer epoch:(uint64_t)epoch
                        emit:(void (^)(NSDictionary *))emit
                       ready:(void (^)(void))ready {
    if ((self = [super init])) {
        _peerId = [peer copy]; _epoch = epoch;
        _emit = [emit copy]; _ready = [ready copy];
        _pending = [NSMutableData new];
    }
    return self;
}
- (void)event:(NSString *)name fields:(NSDictionary *)fields {
    if (self.closed) return;
    NSMutableDictionary *event = [@{@"event": name, @"epoch": @(self.epoch), @"id": self.peerId} mutableCopy];
    [event addEntriesFromDictionary:fields];
    if (self.emit) self.emit(event);
}
- (void)fail:(NSString *)message {
    if (self.closed) return;
    [self event:@"failed" fields:@{@"message": message}];
    [self close];
}
- (void)start {
    if (!AWMacPairingBootstrapAllowed()) {
        [self fail:@"macOS restricts the unencrypted watch pairing channel to applications with Apple's Bluetooth internal entitlement"];
        return;
    }
    Class cls = NSClassFromString(@"CBScalablePipeManager");
    if (!cls || ![cls instancesRespondToSelector:@selector(initWithDelegate:queue:)] ||
        ![cls instancesRespondToSelector:@selector(registerEndpoint:type:priority:options:)] ||
        ![cls instancesRespondToSelector:@selector(unregisterEndpoint:)]) {
        [self fail:@"The Bluetooth pairing stream is unavailable on this macOS version"];
        return;
    }
    CBManager *allocated = [cls alloc];
    self.manager = [allocated initWithDelegate:self queue:dispatch_get_main_queue()];
    [self scalablePipeManagerDidUpdateState:self.manager];
    // A fresh manager per attempt keeps old registration callbacks out of a new epoch.
    __weak AWPairingPipe *weak = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 25 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        AWPairingPipe *owner = weak;
        if (owner && !owner.closed && !owner.opened)
            [owner fail:@"The watch pairing stream did not open (Bluetooth link alone is insufficient)"];
    });
}
- (void)scalablePipeManagerDidUpdateState:(CBManager *)manager {
    if (self.closed || manager != self.manager) return;
    if (manager.state == CBManagerStatePoweredOn && !self.requested) {
        NSString *encryption = option("CBScalablePipeOptionRequiresEncryption");
        NSString *stay = option("CBScalablePipeOptionStayConnectedWhenIdle");
        if (!encryption || !stay) { [self fail:@"Bluetooth pairing options are unavailable"]; return; }
        self.requested = YES;
        [manager registerEndpoint:endpoint type:1 priority:2 options:@{encryption: @NO, stay: @YES}];
    } else if (manager.state == CBManagerStatePoweredOff || manager.state == CBManagerStateUnauthorized ||
               manager.state == CBManagerStateUnsupported) {
        [self fail:@"Bluetooth pairing access is unavailable"];
    }
}
- (void)scalablePipeManager:(id)manager didRegisterEndpoint:(NSString *)name error:(NSError *)error {
    if (self.closed || self.registered || manager != self.manager || ![name isEqualToString:endpoint]) return;
    if (error) {
        [self fail:[NSString stringWithFormat:@"Pairing endpoint registration failed (%ld)", (long)error.code]];
        return;
    }
    self.registered = YES;
    [self event:@"pairing_endpoint_registered" fields:@{}];
    void (^ready)(void) = self.ready;
    self.ready = nil;
    if (ready) ready();
}
- (void)scalablePipeManager:(id)manager didUnregisterEndpoint:(NSString *)name {
    if (!self.closed && manager == self.manager && [name isEqualToString:endpoint])
        [self fail:@"The Bluetooth pairing endpoint was removed"];
}
- (void)scalablePipeManager:(id)manager pipeDidConnect:(id<AWNativePipe>)pipe {
    if (self.closed || manager != self.manager || !self.registered || self.pipe) return;
    for (NSString *selector in @[@"name", @"peer", @"input", @"output"])
        if (![(id)pipe respondsToSelector:NSSelectorFromString(selector)]) return;
    if (![pipe.name isEqualToString:endpoint] ||
        ![pipe.peer.identifier.UUIDString isEqualToString:self.peerId]) return;
    self.pipe = pipe; self.input = pipe.input; self.output = pipe.output;
    if (!self.input || !self.output) { [self fail:@"The Bluetooth pairing pipe has no streams"]; return; }
    self.input.delegate = self; self.output.delegate = self;
    [self.input scheduleInRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
    [self.output scheduleInRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
    [self.input open]; [self.output open];
}
- (void)scalablePipeManager:(id)manager pipeDidDisconnect:(id)pipe error:(NSError *)error {
    if (!self.closed && manager == self.manager && pipe == self.pipe)
        [self fail:@"The watch pairing stream disconnected"];
}
- (void)stream:(NSStream *)stream handleEvent:(NSStreamEvent)event {
    if (self.closed || (stream != self.input && stream != self.output)) return;
    switch (event) {
        case NSStreamEventOpenCompleted:
            if (stream == self.input) self.inputOpen = YES;
            else self.outputOpen = YES;
            if (!self.opened && self.inputOpen && self.outputOpen) {
                self.opened = YES;
                [self event:@"pairing_pipe_opened" fields:@{}];
            }
            break;
        case NSStreamEventHasBytesAvailable: {
            // Yield after a bounded chunk; another run-loop event handles more.
            uint8_t bytes[2048];
            NSInteger length = [self.input read:bytes maxLength:sizeof(bytes)];
            if (length < 0) { [self fail:@"Could not read the watch pairing stream"]; break; }
            if (length == 0) break;
            NSMutableArray *payload = [NSMutableArray arrayWithCapacity:(NSUInteger)length];
            for (NSInteger i = 0; i < length; i++) [payload addObject:@(bytes[i])];
            [self event:@"pairing_pipe_data" fields:@{@"bytes": payload}];
            break;
        }
        case NSStreamEventHasSpaceAvailable: [self drain]; break;
        case NSStreamEventErrorOccurred: [self fail:@"The watch pairing stream failed"]; break;
        case NSStreamEventEndEncountered: [self fail:@"The watch pairing stream ended"]; break;
        default: break;
    }
}
- (void)send:(NSData *)data {
    if (self.closed) return;
    if (!self.opened || data.length > 65536 || self.pending.length + data.length > 65536) {
        [self fail:@"Pairing stream output is unavailable or exceeds its queue limit"];
        return;
    }
    [self.pending appendData:data];
    [self drain];
}
- (void)drain {
    while (!self.closed && self.pending.length && self.output.hasSpaceAvailable) {
        NSInteger length = [self.output write:self.pending.bytes maxLength:self.pending.length];
        if (length < 0) { [self fail:@"Could not write the watch pairing stream"]; return; }
        if (!length) return;
        [self.pending replaceBytesInRange:NSMakeRange(0, (NSUInteger)length) withBytes:NULL length:0];
    }
}
- (void)close {
    if (self.closed) return;
    self.closed = YES; self.ready = nil;
    self.input.delegate = nil; self.output.delegate = nil;
    [self.input close]; [self.output close];
    [self.input removeFromRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
    [self.output removeFromRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
    [self.pending resetBytesInRange:NSMakeRange(0, self.pending.length)];
    [self.pending setLength:0];
    self.input = nil; self.output = nil; self.pipe = nil;
    if (self.requested) [self.manager unregisterEndpoint:endpoint];
}
@end
