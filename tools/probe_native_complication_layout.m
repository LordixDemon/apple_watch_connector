#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Build-time, transient watchOS Simulator models only. No collection/transport API.
static id get(id object, NSString *selector) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(selector));
}
static id one(id object, NSString *selector, id argument) {
    return ((id (*)(id, SEL, id))objc_msgSend)(object, NSSelectorFromString(selector), argument);
}
// Original iOS FaceView delegates hidden-slot queries through FaceViewController
// to the face. A headless FaceView has no delegate and falsely returns no slots.
static NSArray *hidden(id face, NSDictionary *json, id device, NSArray *slots) {
    id configuration = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
        get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
        NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), json, face, device);
    if (!configuration) @throw [NSException exceptionWithName:@"InvalidConfiguration" reason:@"No configuration" userInfo:nil];
    ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), configuration);
    NSDictionary *actual = get(face, @"JSONObjectRepresentation");
    if (![actual[@"customization"] isEqual:json[@"customization"]]) return nil;
    NSArray *visible = get(face, @"allVisibleComplicationsForCurrentConfiguration");
    if (![visible isKindOfClass:NSArray.class]) @throw [NSException exceptionWithName:@"InvalidSlots" reason:@"No native slot list" userInfo:nil];
    NSMutableArray *result = NSMutableArray.array;
    for (NSString *slot in slots) if (![visible containsObject:slot]) [result addObject:slot];
    return result;
}
static void observeConfigurations(id face, id device, NSDictionary *baseline, NSArray *slots,
                   NSArray *fields, NSDictionary *domains, NSMutableDictionary *rules, NSMutableArray *observations,
                   NSUInteger index, NSMutableDictionary *customization,
                   NSUInteger *checked, NSUInteger *normalized) {
    if (index < fields.count) {
        NSString *field = fields[index];
        for (NSString *value in domains[field]) {
            customization[field] = value;
            observeConfigurations(face, device, baseline, slots, fields, domains, rules, observations,
                   index + 1, customization, checked, normalized);
        }
        return;
    }
    @autoreleasepool {
        NSMutableDictionary *json = baseline.mutableCopy;
        json[@"customization"] = customization.copy;
        NSArray *actual = hidden(face, json, device, slots);
        if (!actual) { (*normalized)++; return; }
        NSMutableArray *values = NSMutableArray.array;
        for (NSString *field in fields) {
            NSString *value = customization[field];
            [values addObject:value];
            NSMutableSet *intersection = rules[field][value];
            if (intersection) [intersection intersectSet:[NSSet setWithArray:actual]];
            else rules[field][value] = [NSMutableSet setWithArray:actual];
        }
        [observations addObject:@{@"values": values, @"hidden": actual}];
        (*checked)++;
    }
}
int main(int argc, const char **argv) {
    @autoreleasepool {
        if ((argc != 2 && argc != 3) || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        NSData *input = [NSData dataWithContentsOfFile:@(argv[1])];
        NSDictionary *document = input.length <= 1024 * 1024
            ? [NSJSONSerialization JSONObjectWithData:input options:0 error:nil] : nil;
        NSArray *profiles = document[@"templates"];
        if (![profiles isKindOfClass:NSArray.class] || profiles.count > 256) return 2;
        [NSUserDefaults.standardUserDefaults setVolatileDomain:@{
            @"AppleLanguages": @[@"en-US"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
        NSMutableDictionary *bundles = NSMutableDictionary.dictionary;
        void (^observe)(id) = ^(id bundle) {
            id face = one(bundle, @"defaultFaceForDevice:", device);
            NSDictionary *json = face ? get(face, @"JSONObjectRepresentation") : nil;
            NSString *family = json[@"bundle id"] ? [@"bundle:" stringByAppendingString:json[@"bundle id"]]
                : json[@"face type"] ? [@"type:" stringByAppendingString:json[@"face type"]] : nil;
            if (family) bundles[family] = bundle;
        };
        ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
            NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
        NSMutableArray *rows = NSMutableArray.array, *rejected = NSMutableArray.array;
        for (NSDictionary *profile in profiles) {
            NSString *family = profile[@"family"];
            if (argc == 3 && ![family isEqual:@(argv[2])]) continue;
            @try {
                id face = one(bundles[family], @"defaultFaceForDevice:", device);
                NSDictionary *baseline = profile[@"configuration"];
                if (!face || get(face, @"resourceDirectory") || baseline[@"customData"] || baseline[@"resource directory"] || baseline[@"complications"]) continue;
                NSArray *slots = get(face, @"orderedComplicationSlots");
                if (![slots isKindOfClass:NSArray.class] || slots.count > 32) continue;
                for (NSString *slot in get(face, @"_complicationSlotDescriptors"))
                    ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
                if (!hidden(face, baseline, device, slots)) continue;
                NSMutableDictionary *wireKeys = NSMutableDictionary.dictionary;
                for (NSString *slot in slots) {
                    ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"),
                        get(NSClassFromString(@"NTKComplication"), @"calendarTimelineComplication"), slot);
                    NSDictionary *json = get(face, @"JSONObjectRepresentation");
                    NSDictionary *complications = json[@"complications"];
                    if (complications.count != 1) @throw [NSException exceptionWithName:@"InvalidWireSlot" reason:slot userInfo:nil];
                    wireKeys[slot] = complications.allKeys.firstObject;
                    ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
                }
                NSMutableDictionary *labels = NSMutableDictionary.dictionary;
                for (NSString *slot in slots) labels[slot] = one(face, @"_localizedNameForComplicationSlot:", slot) ?: slot;
                id nativeFixed = one([face class], @"fixedComplicationSlotsForDevice:", device);
                NSArray *fixed = [nativeFixed isKindOfClass:NSSet.class] ? [nativeFixed allObjects] : nativeFixed ?: @[];
                id monogram = one([face class], @"monogramSlotForDevice:", device);
                NSMutableDictionary *domains = NSMutableDictionary.dictionary, *rules = NSMutableDictionary.dictionary;
                for (NSString *field in profile[@"options"]) {
                    NSMutableArray *values = NSMutableArray.array;
                    for (NSDictionary *option in profile[@"options"][field]) [values addObject:option[@"value"]];
                    NSString *original = baseline[@"customization"][field];
                    if ([original isKindOfClass:NSString.class] && ![values containsObject:original]) [values addObject:original];
                    domains[field] = values; rules[field] = NSMutableDictionary.dictionary;
                }
                NSArray *fields = [domains.allKeys sortedArrayUsingSelector:@selector(compare:)];
                NSUInteger combinations = 1;
                for (NSString *field in fields) {
                    NSUInteger count = [domains[field] count];
                    if (!count || combinations > 200000 / count)
                        @throw [NSException exceptionWithName:@"ExcessiveDomain" reason:family userInfo:nil];
                    combinations *= count;
                }
                NSUInteger checked = 0, normalized = 0;
                NSMutableArray *observations = NSMutableArray.array;
                observeConfigurations(face, device, baseline, slots, fields, domains, rules, observations, 0,
                       [baseline[@"customization"] mutableCopy] ?: NSMutableDictionary.dictionary, &checked, &normalized);
                for (NSDictionary *observation in observations) {
                    NSMutableSet *expected = NSMutableSet.set;
                    for (NSUInteger i = 0; i < fields.count; i++)
                        [expected unionSet:rules[fields[i]][observation[@"values"][i]]];
                    if (![expected isEqualToSet:[NSSet setWithArray:observation[@"hidden"]]])
                        @throw [NSException exceptionWithName:@"NonAdditiveLayout" reason:observation.description userInfo:nil];
                }
                NSMutableDictionary *encodedRules = NSMutableDictionary.dictionary;
                for (NSString *field in fields) {
                    NSMutableDictionary *values = NSMutableDictionary.dictionary;
                    for (NSString *value in domains[field]) {
                        NSSet *set = rules[field][value];
                        if (!set) @throw [NSException exceptionWithName:@"UnobservedOption" reason:value userInfo:nil];
                        values[value] = [set.allObjects sortedArrayUsingSelector:@selector(compare:)];
                    }
                    encodedRules[field] = values;
                }
                NSArray *constant = fields.count ? @[] : hidden(face, baseline, device, slots);
                [rows addObject:@{@"family": family, @"order": slots, @"labels": labels, @"wireKeys": wireKeys,
                    @"fixed": fixed, @"monogram": monogram ?: NSNull.null,
                    @"domains": domains, @"rules": encodedRules, @"constant": constant,
                    @"checked": @(checked), @"normalized": @(normalized)}];
                fprintf(stderr, "%s checked=%lu normalized=%lu\n", family.UTF8String, checked, normalized);
            } @catch (NSException *error) {
                [rejected addObject:@{@"family": family, @"reason": error.name, @"detail": error.reason ?: @""}];
                fprintf(stderr, "%s rejected: %s\n", family.UTF8String, error.reason.UTF8String);
            }
        }
        NSDictionary *output = @{@"rows": rows, @"rejected": rejected};
        NSData *data = [NSJSONSerialization dataWithJSONObject:output options:0 error:nil];
        if (!data || data.length > 16 * 1024 * 1024 || !rows.count) return 4;
        fwrite(data.bytes, 1, data.length, stdout);
    }
    return 0;
}
