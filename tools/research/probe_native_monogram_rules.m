#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// TextInput's _containsEmoji uses rangeOfCharacterFromSet:, not sequence parsing.
// Enumerate its scalar membership read-only in watchOS Simulator.
int main(int argc, const char **argv) {
    (void)argv;
    @autoreleasepool {
        if (argc != 1 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        SEL contains = NSSelectorFromString(@"_containsEmoji");
        if (![@"" respondsToSelector:contains]) return 4;
        NSMutableArray *ranges = NSMutableArray.array;
        NSInteger start = -1, end = -1;
        NSUInteger checked = 0;
        for (NSUInteger page = 0; page < 0x110000; page += 4096) {
            @autoreleasepool {
                for (NSUInteger scalar = page; scalar < MIN(page + 4096, 0x110000); scalar++) {
                    if (scalar >= 0xd800 && scalar <= 0xdfff) continue;
                    uint32_t value = (uint32_t)scalar;
                    NSString *text = [[NSString alloc] initWithBytes:&value length:4 encoding:NSUTF32LittleEndianStringEncoding];
                    if (!text) return 5;
                    BOOL emoji = ((BOOL (*)(id, SEL))objc_msgSend)(text, contains);
                    checked++;
                    if (emoji) {
                        if (start < 0) start = scalar;
                        end = scalar;
                    } else if (start >= 0) {
                        [ranges addObject:@[@(start), @(end)]];
                        start = end = -1;
                    }
                }
            }
        }
        if (start >= 0) [ranges addObject:@[@(start), @(end)]];
        NSDictionary *output = @{@"version": @1, @"source": @"watchOS 26.2 / 23S303 TextInput native character set",
            @"checked": @(checked), @"utf16Limit": @5, @"emojiRanges": ranges};
        NSData *data = [NSJSONSerialization dataWithJSONObject:output options:0 error:nil];
        if (!data || data.length > 65536) return 6;
        fwrite(data.bytes, 1, data.length, stdout);
        fputc('\n', stdout);
    }
    return 0;
}
