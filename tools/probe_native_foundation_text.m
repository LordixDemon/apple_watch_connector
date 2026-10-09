#import <Foundation/Foundation.h>
#import <dlfcn.h>
#include <unicode/uchar.h>

// Read-only scalar data for the portable Foundation text implementation.
// No user defaults, NPS, collections, Binder or Bluetooth access.
typedef BOOL (*Membership)(uint32_t, uint32_t);
typedef const uint8_t *(*PropertyPlane)(uint32_t, uint32_t);
typedef CFIndex (*CaseMap)(uint32_t, unichar *, CFIndex, uint32_t, uint32_t, const uint8_t *);
typedef CFIndex (*Decompose)(uint32_t, uint32_t *, CFIndex);
typedef UBool (*BinaryProperty)(UChar32, UProperty);
typedef int32_t (*IntProperty)(UChar32, UProperty);

static void appendRange(NSMutableArray *ranges, NSInteger *start, NSInteger *end, NSUInteger scalar, BOOL member) {
    if (member) {
        if (*start >= 0 && scalar != (NSUInteger)*end + 1) {
            [ranges addObject:@[@(*start), @(*end)]];
            *start = -1;
        }
        if (*start < 0) *start = scalar;
        *end = scalar;
    } else if (*start >= 0) {
        [ranges addObject:@[@(*start), @(*end)]];
        *start = *end = -1;
    }
}

int main(int argc, const char **argv) {
    (void)argv;
    @autoreleasepool {
        if (argc != 1 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        Membership member = (Membership)dlsym(RTLD_DEFAULT, "CFUniCharIsMemberOf");
        PropertyPlane plane = (PropertyPlane)dlsym(RTLD_DEFAULT, "CFUniCharGetUnicodePropertyDataForPlane");
        CaseMap map = (CaseMap)dlsym(RTLD_DEFAULT, "CFUniCharMapCaseTo");
        Decompose decompose = (Decompose)dlsym(RTLD_DEFAULT, "CFUniCharDecomposeCharacter");
        void *icu = dlopen("/usr/lib/libicucore.A.dylib", RTLD_NOW);
        BinaryProperty binary = (BinaryProperty)dlsym(icu, "u_hasBinaryProperty");
        IntProperty property = (IntProperty)dlsym(icu, "u_getIntPropertyValue");
        if (!member || !plane || !map || !decompose || !binary || !property) return 3;
        NSMutableArray *letters = NSMutableArray.array, *nonBase = NSMutableArray.array;
        NSMutableArray *extend = NSMutableArray.array, *pictographic = NSMutableArray.array;
        NSMutableArray *prepend = NSMutableArray.array, *spacing = NSMutableArray.array;
        NSMutableArray *casing = NSMutableArray.array, *combining = NSMutableArray.array, *greek = NSMutableArray.array;
        NSInteger letterStart = -1, letterEnd = -1, markStart = -1, markEnd = -1;
        NSInteger extendStart = -1, extendEnd = -1, picStart = -1, picEnd = -1;
        NSInteger preStart = -1, preEnd = -1, spaceStart = -1, spaceEnd = -1;
        NSUInteger checked = 0, matches = 0;
        for (NSUInteger page = 0; page < 0x110000; page += 4096) {
            @autoreleasepool {
                for (NSUInteger scalar = page; scalar < MIN(page + 4096, 0x110000); scalar++) {
                    if (scalar >= 0xd800 && scalar <= 0xdfff) continue;
                    // CoreFoundation's exported ABI: letter=5, nonbase=8,
                    // combining property=0, uppercase case-map=1, flags=0/no language.
                    appendRange(letters, &letterStart, &letterEnd, scalar, member((uint32_t)scalar, 5));
                    appendRange(nonBase, &markStart, &markEnd, scalar, member((uint32_t)scalar, 8));
                    appendRange(extend, &extendStart, &extendEnd, scalar,
                        binary((UChar32)scalar, UCHAR_GRAPHEME_EXTEND) || binary((UChar32)scalar, UCHAR_EMOJI_MODIFIER));
                    appendRange(pictographic, &picStart, &picEnd, scalar,
                        binary((UChar32)scalar, UCHAR_EXTENDED_PICTOGRAPHIC));
                    int32_t gcb = property((UChar32)scalar, UCHAR_GRAPHEME_CLUSTER_BREAK);
                    appendRange(prepend, &preStart, &preEnd, scalar, gcb == U_GCB_PREPEND);
                    appendRange(spacing, &spaceStart, &spaceEnd, scalar, gcb == U_GCB_SPACING_MARK);
                    const uint8_t *data = plane(0, (uint32_t)(scalar >> 16));
                    uint16_t low = (uint16_t)scalar;
                    uint8_t block = data ? data[low >> 8] : 0;
                    uint8_t ccc = block ? data[256 + (block - 1) * 256 + (low & 255)] : 0;
                    if (ccc) [combining addObject:@[@(scalar), @(ccc)]];
                    uint32_t value = (uint32_t)scalar;
                    NSString *text = [[NSString alloc] initWithBytes:&value length:4 encoding:NSUTF32LittleEndianStringEncoding];
                    unichar buffer[16];
                    CFIndex size = map(value, buffer, 16, 1, 0, NULL);
                    if (!text || size < 1 || size > 16) return 4;
                    NSString *mapped = [NSString stringWithCharacters:buffer length:(NSUInteger)size];
                    if (![mapped isEqualToString:text.uppercaseString]) return 5;
                    matches++;
                    if (![mapped isEqualToString:text]) [casing addObject:@[@(scalar), mapped]];
                    if ((scalar >= 0x0370 && scalar < 0x0400) || (scalar >= 0x1f00 && scalar < 0x2000)) {
                        uint32_t decomposition[64];
                        CFIndex length = decompose(value, decomposition, 64);
                        if (length < 0 || length > 64) return 6;
                        if (length > 1) {
                            NSMutableArray *units = NSMutableArray.array;
                            for (CFIndex i = 0; i < length; i++) [units addObject:@(decomposition[i])];
                            [greek addObject:@[@(scalar), units]];
                        }
                    }
                    checked++;
                }
            }
        }
        if (letterStart >= 0) [letters addObject:@[@(letterStart), @(letterEnd)]];
        if (markStart >= 0) [nonBase addObject:@[@(markStart), @(markEnd)]];
        if (extendStart >= 0) [extend addObject:@[@(extendStart), @(extendEnd)]];
        if (picStart >= 0) [pictographic addObject:@[@(picStart), @(picEnd)]];
        if (preStart >= 0) [prepend addObject:@[@(preStart), @(preEnd)]];
        if (spaceStart >= 0) [spacing addObject:@[@(spaceStart), @(spaceEnd)]];
        NSDictionary *output = @{@"schema": @2, @"runtime": NSProcessInfo.processInfo.operatingSystemVersionString,
            @"checked": @(checked), @"uppercaseMatches": @(matches), @"uppercase": casing,
            @"letterRanges": letters, @"nonBaseRanges": nonBase, @"combiningClasses": combining,
            @"greekDecomposition": greek, @"extendRanges": extend, @"pictographicRanges": pictographic,
            @"prependRanges": prepend, @"spacingRanges": spacing};
        NSData *json = [NSJSONSerialization dataWithJSONObject:output options:0 error:nil];
        if (!json || json.length > 1024 * 1024) return 7;
        fwrite(json.bytes, 1, json.length, stdout);
        fputc('\n', stdout);
    }
    return 0;
}
