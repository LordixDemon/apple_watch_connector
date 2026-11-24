#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Read-only, simulator-only collection metadata; never connects to a Watch.
static id get(id o, NSString *s) { return ((id (*)(id, SEL))objc_msgSend)(o, NSSelectorFromString(s)); }
static BOOL flag(id o, NSString *s) { return ((BOOL (*)(id, SEL))objc_msgSend)(o, NSSelectorFromString(s)); }
static id slot(id o, NSString *s) { return ((id (*)(id, SEL, id))objc_msgSend)(o, NSSelectorFromString(s), nil); }
int main(void) { @autoreleasepool {
    if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    [NSUserDefaults.standardUserDefaults setVolatileDomain:@{@"AppleLanguages": @[@"en-US"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
    id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
    id store = get(NSClassFromString(@"NTKPigmentEditOptionStore"), @"sharedInstance");
    NSMutableSet *defaults = NSMutableSet.set;
    // Match the native store's automatic initialization from shared collections,
    // rather than baking this simulator's existing user preferences into assets.
    for (id collection in [get(store, @"sharedCollections") allValues]) {
        if (!flag(collection, @"isRootCollection")) continue;
        for (id option in get(collection, @"pigmentsFromMostRecentAddableCollection"))
            [defaults addObject:get(option, @"fullname")];
    }
    NSMutableArray *rows = NSMutableArray.array;
    __block BOOL failed = NO;
    void (^observe)(id) = ^(id bundle) { @try {
        id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle, NSSelectorFromString(@"defaultFaceForDevice:"), device);
        NSDictionary *initial = face ? get(face, @"JSONObjectRepresentation") : nil;
        if (!initial || initial[@"customData"] || initial[@"resource directory"] || get(face, @"resourceDirectory") || !flag(face, @"supportsPigmentUI")) return;
        BOOL globalColor = NO;
        for (id section in get(face, @"editOptionsForCustomEditModes")) {
            NSUInteger mode = ((NSUInteger (*)(id, SEL))objc_msgSend)(section, NSSelectorFromString(@"mode"));
            if (mode == 10 && !get(section, @"slot")) globalColor = YES;
        }
        if (!globalColor) return; // Slot-specific pigment editors are separate.
        for (NSString *s in get(face, @"_complicationSlotDescriptors"))
            ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, s);
        NSDictionary *baseline = get(face, @"JSONObjectRepresentation");
        id config = ((id (*)(id, SEL, id, id, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
            NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), baseline, face, device);
        ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), config);
        baseline = get(face, @"JSONObjectRepresentation");
        NSString *family = baseline[@"bundle id"] ? [@"bundle:" stringByAppendingString:baseline[@"bundle id"]]
            : baseline[@"face type"] ? [@"type:" stringByAppendingString:baseline[@"face type"]] : nil;
        id provider = get(face, @"pigmentOptionProvider");
        NSArray *options = slot(provider, @"availableColorsForSlot:");
        if (!family || !options.count || options.count > 512 || rows.count >= 256) { failed = YES; return; }
        id defaultOption = slot(provider, @"defaultColorOptionForSlot:");
        ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face, NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), defaultOption, 10, nil);
        NSDictionary *defaultCustomization = get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{};
        NSMutableArray *values = NSMutableArray.array;
        for (id option in options) {
            ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), config);
            ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face, NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), option, 10, nil);
            [values addObject:@{@"customization": get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{},
                @"fullname": get(option, @"fullname") ?: @"", @"collection": get(option, @"collectionName") ?: @"",
                @"title": get(option, @"localizedCollectionName") ?: @"", @"addable": @(flag(option, @"isAddable"))}];
        }
        [rows addObject:@{@"family": family, @"baseline": baseline[@"customization"] ?: @{},
            @"defaultCustomization": defaultCustomization, @"values": values}];
    } @catch (NSException *error) { failed = YES; fprintf(stderr, "Collection unavailable: %s\n", error.reason.UTF8String); }};
    ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
        NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
    NSData *data = [NSJSONSerialization dataWithJSONObject:@{@"defaults": [defaults.allObjects sortedArrayUsingSelector:@selector(compare:)], @"families": rows} options:0 error:nil];
    if (failed || !data || data.length > 8 * 1024 * 1024 || !rows.count) return 4;
    fwrite(data.bytes, 1, data.length, stdout); return 0;
}}
