#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Simulator-only read-only metadata research. No Watch transport, collection
// writes, or application launch. Use only classes published by the native bundle.
static id send0(id value, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(value, NSSelectorFromString(name));
}
int main(void) {
    @autoreleasepool {
        if (!getenv("SIMULATOR_ROOT")) return 1;
        [[NSUserDefaults standardUserDefaults] setVolatileDomain:
            @{@"AppleLanguages": @[@"en"], @"AppleLocale": @"en_US"}
            forName:NSArgumentDomain];
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)
            || !dlopen("/System/Library/NanoTimeKit/ComplicationBundles/NanoCompassComplications.bundle/NanoCompassComplications", RTLD_NOW)) {
            fprintf(stderr, "Native metadata unavailable: %s\n", dlerror()); return 2;
        }
        id device = send0(NSClassFromString(@"CLKDevice"), @"currentDevice");
        id sources = send0(NSClassFromString(@"NanoCompassComplicationsBundleDataSourceContainer"), @"complicationBundleDataSources");
        if (!device || ![sources isKindOfClass:NSArray.class]) return 3;
        NSMutableArray *rows = [NSMutableArray array];
        for (Class source in sources) {
            @try {
                id bundle = send0(source, @"bundleIdentifier");
                id app = send0(source, @"appIdentifier");
                if (![bundle isKindOfClass:NSString.class] || ![app isKindOfClass:NSString.class]) continue;
                NSMutableArray *families = [NSMutableArray array];
                for (NSUInteger family = 0; family <= 12; family++) {
                    if (((BOOL (*)(id, SEL, NSUInteger, id))objc_msgSend)(source,
                        NSSelectorFromString(@"acceptsComplicationFamily:forDevice:"), family, device)) {
                        [families addObject:@(family)];
                    }
                }
                id complication = ((id (*)(id, SEL, id, id))objc_msgSend)(
                    NSClassFromString(@"NTKBundleComplication"),
                    NSSelectorFromString(@"bundledComplicationWithBundleIdentifier:appBundleIdentifier:"), bundle, app);
                id json = complication ? send0(complication, @"JSONObjectRepresentation") : nil;
                NSMutableDictionary *row = [@{
                    @"class": NSStringFromClass(source), @"app": app, @"bundle": bundle,
                    @"families": families,
                    @"nameKey": send0(source, @"complicationNameLocalizationKey") ?: @"",
                    @"localizedName": send0(source, @"localizedComplicationName") ?: @"",
                    @"localizedApp": send0(source, @"localizedAppName") ?: @""
                } mutableCopy];
                if ([json isKindOfClass:NSDictionary.class]) {
                    row[@"configuration"] = json;
                    id decoded = ((id (*)(id, SEL, id))objc_msgSend)(NSClassFromString(@"NTKComplication"),
                        NSSelectorFromString(@"complicationWithJSONObjectRepresentation:"), json);
                    row[@"roundtripEqual"] = @([json isEqual:send0(decoded, @"JSONObjectRepresentation")]);
                }
                [rows addObject:row];
            } @catch (NSException *exception) {
                fprintf(stderr, "%s: %s\n", NSStringFromClass(source).UTF8String, exception.reason.UTF8String);
            }
        }
        NSData *data = [NSJSONSerialization dataWithJSONObject:rows options:NSJSONWritingPrettyPrinted error:nil];
        if (!data) return 4;
        fwrite(data.bytes, 1, data.length, stdout); fputc('\n', stdout);
    }
    return 0;
}
