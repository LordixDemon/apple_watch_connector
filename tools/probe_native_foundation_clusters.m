#import <Foundation/Foundation.h>

// Exhaustive read-only boundary oracle. All strings are transient; no preferences.
static NSString *character(uint32_t value) {
    return [[NSString alloc] initWithBytes:&value length:4 encoding:NSUTF32LittleEndianStringEncoding];
}
int main(int argc, const char **argv) {
    (void)argv;
    @autoreleasepool {
        if (argc != 1 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        NSArray *prefixes = @[@"abcd", @"abc", @"abcd", @"abc", @"abcd"];
        NSArray *suffixes = @[@"A", @"A", @"\u0301A", @"\u200dA", @"\u200d👩"];
        NSMutableArray *templates = NSMutableArray.array;
        for (NSUInteger pattern = 0; pattern < prefixes.count; pattern++) {
            NSMutableArray *differences = NSMutableArray.array;
            NSUInteger checked = 0;
            for (uint32_t page = 0; page < 0x110000; page += 4096) {
                @autoreleasepool {
                    for (uint32_t cp = page; cp < MIN(page + 4096, 0x110000); cp++) {
                        if (cp >= 0xd800 && cp <= 0xdfff) continue;
                        NSString *text = [NSString stringWithFormat:@"%@%@%@", prefixes[pattern], character(cp), suffixes[pattern]];
                        NSUInteger limit = MIN(text.length, 5);
                        NSRange range = [text rangeOfComposedCharacterSequencesForRange:NSMakeRange(0, limit)];
                        NSUInteger defaultEnd = limit;
                        if (defaultEnd < text.length) {
                            unichar previous = [text characterAtIndex:defaultEnd - 1];
                            if (previous >= 0xd800 && previous <= 0xdbff) defaultEnd++;
                        }
                        if (range.location != 0 || NSMaxRange(range) > text.length) return 3;
                        if (NSMaxRange(range) != defaultEnd) {
                            NSArray *last = differences.lastObject;
                            if (last && [last[1] unsignedIntValue] + 1 == cp && [last[2] unsignedIntegerValue] == NSMaxRange(range)) {
                                differences[differences.count - 1] = @[last[0], @(cp), last[2]];
                            } else [differences addObject:@[@(cp), @(cp), @(NSMaxRange(range))]];
                        }
                        checked++;
                    }
                }
            }
            [templates addObject:@{@"prefix": prefixes[pattern], @"suffix": suffixes[pattern],
                @"checked": @(checked), @"differentEnds": differences}];
        }
        NSDictionary *result = @{@"schema": @2, @"runtime": NSProcessInfo.processInfo.operatingSystemVersionString,
            @"utf16Limit": @5, @"templates": templates};
        NSData *json = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
        if (!json || json.length > 4 * 1024 * 1024) return 4;
        fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout);
    }
    return 0;
}
