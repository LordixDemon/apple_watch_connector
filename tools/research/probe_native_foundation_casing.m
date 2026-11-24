#import <Foundation/Foundation.h>

// Read-only contextual casing oracle. Does not truncate or persist text.
int main(int argc, const char **argv) {
    (void)argv;
    @autoreleasepool {
        if (argc != 1 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        NSArray *locales = @[@"en_US", @"tr_TR", @"az_AZ", @"lt_LT", @"el_GR", @"nl_NL", @"de_DE", @"ja_JP"];
        NSMutableArray *inputs = [@[@"i", @"i̇", @"ị̇", @"i̇́", @"ị́", @"j̇", @"j̣̇", @"į̇", @"İ",
            @"ßﬃ", @"áé", @"σ́", @"Σ́", @"σ̣́", @"Σ̣́", @"α̈́", @"ΐ", @"ᾄ", @"ᾀ", @"𐐨𐐨"] mutableCopy];
        for (NSUInteger scalar = 0x0370; scalar < 0x2000; scalar++) {
            if (scalar >= 0x0400 && scalar < 0x1f00) continue;
            unichar unit = (unichar)scalar;
            NSString *text = [NSString stringWithCharacters:&unit length:1];
            [inputs addObject:text];
            [inputs addObject:[text stringByAppendingString:@"́"]];
            [inputs addObject:[text stringByAppendingString:@"̣́"]];
        }
        NSMutableArray *rows = NSMutableArray.array;
        for (NSString *locale in locales) for (NSString *input in inputs) {
            [rows addObject:@{@"input": input, @"locale": locale,
                @"uppercase": [input uppercaseStringWithLocale:[NSLocale localeWithLocaleIdentifier:locale]]}];
        }
        NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"schema": @1,
            @"runtime": NSProcessInfo.processInfo.operatingSystemVersionString, @"cases": rows} options:0 error:nil];
        if (!json || json.length > 2 * 1024 * 1024) return 3;
        fwrite(json.bytes, 1, json.length, stdout);
        fputc('\n', stdout);
    }
    return 0;
}
