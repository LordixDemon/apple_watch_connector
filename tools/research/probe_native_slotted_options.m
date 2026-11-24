#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Read-only simulator research: enumerate native slotted customization schemas.
// No transport, Watch library, user resource directory or paired-device API.
static id get(id object, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static void apply(id face, id configuration) {
    ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), configuration);
}
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        [NSUserDefaults.standardUserDefaults setVolatileDomain:@{
            @"AppleLanguages": @[@"en-US"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
        NSArray *contexts = nil;
        if (argc > 2) return 2;
        if (argc == 2) {
            NSData *input = [NSData dataWithContentsOfFile:@(argv[1])];
            if (!input || input.length > 1024 * 1024) return 2;
            contexts = [NSJSONSerialization JSONObjectWithData:input options:0 error:nil];
            if (![contexts isKindOfClass:NSArray.class] || !contexts.count || contexts.count > 256) return 2;
            for (id row in contexts) {
                if (![row isKindOfClass:NSDictionary.class] ||
                    ![row[@"family"] isKindOfClass:NSString.class] ||
                    ![row[@"customization"] isKindOfClass:NSDictionary.class]) return 2;
            }
        }
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
        NSMutableArray *rows = NSMutableArray.array;
        void (^observe)(id) = ^(id bundle) { @try {
            id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle,
                NSSelectorFromString(@"defaultFaceForDevice:"), device);
            NSDictionary *initial = face ? get(face, @"JSONObjectRepresentation") : nil;
            if (!initial || initial[@"customData"] || initial[@"resource directory"] || get(face, @"resourceDirectory")) return;
            for (NSString *slot in get(face, @"_complicationSlotDescriptors"))
                ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
            initial = get(face, @"JSONObjectRepresentation");
            id config = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
                get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
                NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), initial, face, device);
            apply(face, config);
            NSDictionary *baseline = get(face, @"JSONObjectRepresentation");
            NSString *family = baseline[@"bundle id"] ? [@"bundle:" stringByAppendingString:baseline[@"bundle id"]]
                : baseline[@"face type"] ? [@"type:" stringByAppendingString:baseline[@"face type"]] : nil;
            if (!family) return;
            NSArray *matching = contexts ? [contexts filteredArrayUsingPredicate:
                [NSPredicate predicateWithBlock:^BOOL(NSDictionary *row, NSDictionary *bindings) {
                    return [row[@"family"] isEqual:family];
                }]] : @[@{@"family": family, @"customization": baseline[@"customization"] ?: @{}}];
            for (NSDictionary *context in matching) {
                NSMutableDictionary *requested = [baseline mutableCopy];
                requested[@"customization"] = context[@"customization"];
                id contextConfig = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
                    get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
                    NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), requested, face, device);
                apply(face, contextConfig);
                NSDictionary *actual = get(face, @"JSONObjectRepresentation");
                if (![(actual[@"customization"] ?: @{}) isEqual:requested[@"customization"]]) {
                    fprintf(stderr, "Native slotted context normalized; refusing its options\n");
                    continue;
                }
            for (NSNumber *number in get(face, @"customEditModes")) {
                NSUInteger mode = number.unsignedIntegerValue;
                id slots = ((id (*)(id, SEL, NSUInteger))objc_msgSend)(face,
                    NSSelectorFromString(@"slotsForCustomEditMode:"), mode);
                if (![slots isKindOfClass:NSArray.class] || ![slots count] || [slots count] > 64) continue;
                for (id slot in slots) {
                    NSUInteger count = ((NSUInteger (*)(id, SEL, NSUInteger, id))objc_msgSend)(face,
                        NSSelectorFromString(@"numberOfOptionsForCustomEditMode:slot:"), mode, slot);
                    if (!count || count > 512 || rows.count >= 10000) continue;
                    NSMutableArray *samples = NSMutableArray.array;
                    // Enumerate the complete bounded native option set. A full
                    // configuration is retained for verifying nested field diffs.
                    for (NSUInteger index = 0; index < count; index++) {
                        apply(face, contextConfig);
                        id option = ((id (*)(id, SEL, NSUInteger, NSUInteger, id))objc_msgSend)(face,
                            NSSelectorFromString(@"optionAtIndex:forCustomEditMode:slot:"), index, mode, slot);
                        ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face,
                            NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), option, mode, slot);
                        NSString *label = [option respondsToSelector:NSSelectorFromString(@"localizedName")]
                            ? get(option, @"localizedName") : nil;
                        [samples addObject:@{@"index": @(index), @"label": label ?: @"",
                            @"customization": get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{}}];
                    }
                    [rows addObject:@{@"family": family, @"mode": number,
                        @"slot": [slot description], @"count": @(count),
                        @"baseline": actual[@"customization"] ?: @{}, @"samples": samples}];
                }
                apply(face, contextConfig);
            }
            }
        } @catch (NSException *error) {
            fprintf(stderr, "Native slotted mode unavailable: %s\n", error.reason.UTF8String);
        }};
        ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
            NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
        NSData *data = [NSJSONSerialization dataWithJSONObject:rows options:0 error:nil];
        if (!data || data.length > 16 * 1024 * 1024) return 4;
        fwrite(data.bytes, 1, data.length, stdout);
        return 0;
    }
}
