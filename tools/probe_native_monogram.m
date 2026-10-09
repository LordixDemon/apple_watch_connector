#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>

// Read-only transient watchOS Simulator probe. No preference/collection writes.
static id get(id object, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static BOOL boolean(id object, NSString *name) {
    return ((BOOL (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
int main(int argc, const char **argv) {
    (void)argv;
    @autoreleasepool {
        if (argc != 1) return 2;
        if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        [NSUserDefaults.standardUserDefaults setVolatileDomain:@{
            @"AppleLanguages": @[@"en-US"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
        NSMutableDictionary *result = NSMutableDictionary.dictionary;
        NSArray *samples = @[@"", @"A", @"ABCDE", @"ABCDEF", @"é", @"你好", @"𐐀", @"😀", @"☀", @"☀︎", @"☀️",
            @"©", @"©︎", @"©️", @"1", @"1⃣", @"1️⃣", @"🇺", @"🇺🇦", @"AB CD", @"i", @"ß", @"é", @"ééé"];
        NSMutableArray *values = NSMutableArray.array;
        BOOL supports = [@"" respondsToSelector:NSSelectorFromString(@"ntk_isValidMonogram")];
        BOOL emoji = [@"" respondsToSelector:NSSelectorFromString(@"_containsEmoji")];
        for (NSString *sample in samples) {
            NSMutableDictionary *row = [@{@"text": sample, @"utf16Length": @(sample.length),
                @"uppercase": [sample uppercaseStringWithLocale:[NSLocale localeWithLocaleIdentifier:@"en_US"]]} mutableCopy];
            if (supports) row[@"valid"] = @(boolean(sample, @"ntk_isValidMonogram"));
            if (emoji) row[@"emoji"] = @(boolean(sample, @"_containsEmoji"));
            [values addObject:row];
        }
        result[@"samples"] = values;
        result[@"supportsValidMonogram"] = @(supports);
        result[@"supportsEmoji"] = @(emoji);
        if (emoji) {
            IMP implementation = class_getMethodImplementation(NSString.class, NSSelectorFromString(@"_containsEmoji"));
            Dl_info info = {0};
            if (dladdr(implementation, &info)) {
                result[@"emojiImage"] = info.dli_fname ? @(info.dli_fname) : @"";
                result[@"emojiSymbol"] = info.dli_sname ? @(info.dli_sname) : @"";
            }
        }
        id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
        id complicationClass = NSClassFromString(@"NTKComplication");
        id enabled = ((id (*)(id, SEL, NSUInteger))objc_msgSend)(complicationClass,
            NSSelectorFromString(@"anyComplicationOfType:"), 14);
        id disabled = get(complicationClass, @"nullComplication");
        NSMutableArray *complications = NSMutableArray.array;
        for (NSUInteger type = 0; type <= 64; type++) {
            NSArray *choices = ((id (*)(id, SEL, NSUInteger))objc_msgSend)(NSClassFromString(@"NTKComplication"),
                NSSelectorFromString(@"allComplicationsOfType:"), type);
            for (id complication in choices) {
                if ([NSStringFromClass([complication class]) containsString:@"Monogram"])
                    [complications addObject:get(complication, @"JSONObjectRepresentation")];
            }
        }
        result[@"monogramComplications"] = complications;
        NSMutableArray *faces = NSMutableArray.array;
        void (^observe)(id) = ^(id bundle) { @try {
            id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle, NSSelectorFromString(@"defaultFaceForDevice:"), device);
            id slot = ((id (*)(id, SEL, id))objc_msgSend)([face class], NSSelectorFromString(@"monogramSlotForDevice:"), device);
            if (!slot) return;
            NSDictionary *config = get(face, @"JSONObjectRepresentation");
            NSString *family = config[@"bundle id"] ? [@"bundle:" stringByAppendingString:config[@"bundle id"]]
                : config[@"face type"] ? [@"type:" stringByAppendingString:config[@"face type"]] : nil;
            if (!family) return;
            ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), enabled, slot);
            NSDictionary *on = get(face, @"JSONObjectRepresentation");
            ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), disabled, slot);
            NSDictionary *off = get(face, @"JSONObjectRepresentation");
            [faces addObject:@{@"family": family, @"slot": slot, @"configuration": config,
                @"enabledConfiguration": on, @"disabledConfiguration": off,
                @"enabled": get(enabled, @"JSONObjectRepresentation") ?: NSNull.null,
                @"disabled": get(disabled, @"JSONObjectRepresentation") ?: NSNull.null}];
        } @catch (NSException *exception) { fprintf(stderr, "%s\n", exception.reason.UTF8String); }};
        ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
            NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
        result[@"faces"] = faces;
        NSData *data = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:nil];
        if (!data || data.length > 1024 * 1024) return 4;
        fwrite(data.bytes, 1, data.length, stdout);
    }
    return 0;
}
