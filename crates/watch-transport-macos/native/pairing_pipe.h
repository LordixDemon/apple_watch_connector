#import <Foundation/Foundation.h>
BOOL AWMacPairingBootstrapAllowed(void);

// Main-queue confined. Each owner represents exactly one selected peer/epoch.
@interface AWPairingPipe : NSObject
- (instancetype)initWithPeer:(NSString *)peer epoch:(uint64_t)epoch
                        emit:(void (^)(NSDictionary *))emit
                       ready:(void (^)(void))ready;
- (void)start;
- (void)send:(NSData *)data;
- (void)close;
@end
