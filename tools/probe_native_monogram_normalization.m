#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Transient models only. No preference writes, NPS, Bluetooth or collection access.
int main(int argc, const char **argv) {
    (void)argv;
    @autoreleasepool {
        if (argc != 1 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        SEL validator = NSSelectorFromString(@"ntk_isValidMonogram");
        if (![@"" respondsToSelector:validator]) return 4;
        NSArray *locales = @[@"en_US", @"tr_TR", @"az_AZ", @"de_DE", @"lt_LT", @"el_GR", @"ja_JP"];
        NSMutableArray *texts = [@[@"", @" ", @"ab cd", @"abcdef", @"i", @"ı", @"ißi", @"ßßß", @"abcdß",
            @"straße", @"ﬃﬃ", @"é", @"ééé", @"abcdé", @"abcdé", @"abc각", @"ab𐐨𐐨",
            @"a𐐨𐐨", @"abcd𐐨", @"你好世界啊呀", @"i̇", @"ΐ", @"ά", @"άέήίόύώ", @"©", @"☀︎",
            @"1️⃣", @"🇺🇦", @"abcd🇺🇦", @"abcd👩‍💻", @"a\r\nbcd", @"\t a\n", @"\0ab",
            @"abक्‍ष", @"abcक्‍ष", @"abक्ष", @"abcक्ष", @"ab\u0600A", @"abcd\u0600A",
            @"ab\u200dcd", @"ab\u200bcd", @"ab\u0301\u0302\u0303\u0304\u0305c"] mutableCopy];
        [texts addObjectsFromArray:@[@"abcd\r\n", @"abcd\n\u0301", @"abcd\0\u0301", @"abcd\u200dA",
            @"abcd\u034fA", @"abcक्‍षZ", @"abcᄀ가ᅡᆨᆨ", @"abc각ᆨᆨ", @"abc가ᅡᆨ",
            @"ab\u0600👩‍💻X", @"abc👩🏿‍✈️X", @"abcd👱‍♀️👨‍👦", @"abc👩‍‍👨",
            @"abc👩‍́👨", @"abc👩́‍👨", @"abc💘\u108f\u103dX", @"abc🇺🇦🇯🇵",
            @"ab\uf860ABCD", @"ab\uf862ABĆDX", @"abcd\uf870X", @"abcd\uff9eX"]];
        // Cross-script pairs around the truncation boundary, including supplementary marks.
        NSArray *units = @[@"A", @"\u0301", @"\u034f", @"\u0600", @"\u094d", @"क", @"\u200d",
            @"\u1100", @"\u1160", @"\u11a8", @"가", @"각", @"👩", @"🏿", @"\ufe0f", @"\uf860", @"𝅥"];
        for (NSString *first in units) for (NSString *second in units) {
            [texts addObject:[NSString stringWithFormat:@"abc%@%@AZ", first, second]];
            [texts addObject:[NSString stringWithFormat:@"abcd%@%@AZ", first, second]];
        }
        NSArray *hangul = @[@"\u115f", @"\u1160", @"\u11a2", @"\u11a3", @"\u11a7", @"\u11a8",
            @"\u11f9", @"\u11fa", @"\u11ff", @"\ud7a3", @"\ud7a4", @"\ud7af"];
        for (NSString *first in hangul) for (NSString *second in hangul)
            [texts addObject:[NSString stringWithFormat:@"abcd%@%@AZ", first, second]];
        for (NSUInteger size = 32; size <= 256; size *= 2) {
            NSString *marks = [@"" stringByPaddingToLength:size withString:@"\u0301" startingAtIndex:0];
            NSString *spaces = [@"" stringByPaddingToLength:size withString:@"\u0903" startingAtIndex:0];
            NSString *joiners = [@"" stringByPaddingToLength:size withString:@"\u200d" startingAtIndex:0];
            NSMutableString *people = NSMutableString.string;
            for (NSUInteger i = 0; i < size; i++) [people appendString:@"👩‍"];
            [texts addObjectsFromArray:@[[NSString stringWithFormat:@"abcd%@A", marks],
                [NSString stringWithFormat:@"abc👩%@A", marks], [NSString stringWithFormat:@"abc👩%@A", spaces],
                [NSString stringWithFormat:@"abc👩%@A", joiners], [NSString stringWithFormat:@"abc%@A", people]]];
        }
        [texts addObjectsFromArray:@[@"abக்ஷZ", @"abஶ்ரீZ", @"abஸ்ரீZ", @"abக்ரZ", @"abக்ஷ்Z"]];
        NSMutableArray *rows = NSMutableArray.array;
        for (NSString *locale in locales) for (NSString *text in texts) {
            // Original didEndEditing: composed-range expansion THEN locale uppercase.
            NSRange range = [text rangeOfComposedCharacterSequencesForRange:NSMakeRange(0, MIN(text.length, 5))];
            NSString *normalized = [[text substringWithRange:range] uppercaseStringWithLocale:
                [NSLocale localeWithLocaleIdentifier:locale]];
            BOOL valid = ((BOOL (*)(id, SEL))objc_msgSend)(normalized, validator);
            [rows addObject:@{@"input": text, @"locale": locale, @"normalized": normalized,
                @"valid": @(valid), @"utf16Length": @(normalized.length)}];
        }
        NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"schema": @1, @"utf16Limit": @5,
            @"runtime": @"watchOS 26.2 / 23S303", @"cases": rows} options:0 error:nil];
        if (!json || json.length > 4 * 1024 * 1024) return 5;
        fwrite(json.bytes, 1, json.length, stdout);
    }
    return 0;
}
