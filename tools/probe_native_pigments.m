#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>
#import <CommonCrypto/CommonDigest.h>

// Simulator-only native color data. Never opens a Watch transport or library.
static id get(id o, NSString *s) { return ((id (*)(id, SEL))objc_msgSend)(o, NSSelectorFromString(s)); }
static BOOL flag(id o, NSString *s) { return [o respondsToSelector:NSSelectorFromString(s)] && ((BOOL (*)(id, SEL))objc_msgSend)(o, NSSelectorFromString(s)); }
static NSUInteger number(id o, NSString *s) { return ((NSUInteger (*)(id, SEL))objc_msgSend)(o, NSSelectorFromString(s)); }
static void apply(id face, id config) { ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), config); }
static NSArray *rgba(id color) {
    if (![color respondsToSelector:NSSelectorFromString(@"CGColor")]) return nil;
    CGColorRef original = ((CGColorRef (*)(id, SEL))objc_msgSend)(color, NSSelectorFromString(@"CGColor"));
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceExtendedSRGB);
    CGColorRef converted = original ? CGColorCreateCopyByMatchingToColorSpace(space, kCGRenderingIntentDefault, original, NULL) : NULL;
    CGColorSpaceRelease(space);
    if (!converted) return nil;
    NSArray *result = nil;
    if (CGColorGetNumberOfComponents(converted) == 4) {
        const CGFloat *c = CGColorGetComponents(converted);
        if (isfinite(c[0]) && isfinite(c[1]) && isfinite(c[2]) && isfinite(c[3]))
            result = @[@(c[0]), @(c[1]), @(c[2]), @(c[3])];
    }
    CGColorRelease(converted); return result;
}
int main(int argc, const char *argv[]) { @autoreleasepool {
    if (argc < 2 || argc > 66 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    BOOL complexOnly = argc > 2 && strcmp(argv[2], "--complex-only") == 0;
    BOOL supportOnly = argc > 2 && strcmp(argv[2], "--support-only") == 0;
    if (complexOnly && argc < 4) return 2;
    NSMutableSet *families = NSMutableSet.set;
    for (int i = complexOnly || supportOnly ? 3 : 2; i < argc; i++) {
        NSString *family = @(argv[i]);
        if (family.length > 256 || (![family hasPrefix:@"type:"] && ![family hasPrefix:@"bundle:"])) return 2;
        [families addObject:family];
    }
    NSString *directory = @(argv[1]);
    if ([NSFileManager.defaultManager fileExistsAtPath:directory] ||
        ![NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil]) return 2;
    [NSUserDefaults.standardUserDefaults setVolatileDomain:@{@"AppleLanguages": @[@"en-US"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
    id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
    NSMutableArray *rows = NSMutableArray.array;
    __block NSUInteger images = 0;
    __block BOOL invalidImage = NO;
    NSMutableSet *imageDigests = NSMutableSet.set;
    void (^observe)(id) = ^(id bundle) { @try {
        id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle, NSSelectorFromString(@"defaultFaceForDevice:"), device);
        NSDictionary *initial = face ? get(face, @"JSONObjectRepresentation") : nil;
        if (!initial || initial[@"customData"] || initial[@"resource directory"] || get(face, @"resourceDirectory")) return;
        for (NSString *slot in get(face, @"_complicationSlotDescriptors"))
            ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
        id config = ((id (*)(id, SEL, id, id, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
            NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), get(face, @"JSONObjectRepresentation"), face, device);
        apply(face, config);
        NSDictionary *baseline = get(face, @"JSONObjectRepresentation");
        NSString *family = baseline[@"bundle id"] ? [@"bundle:" stringByAppendingString:baseline[@"bundle id"]]
            : baseline[@"face type"] ? [@"type:" stringByAppendingString:baseline[@"face type"]] : nil;
        if (families.count && ![families containsObject:family]) return;
        // The controller is the native palette provider for views that do not
        // override createFaceColorPalette. An unattached faceView silently
        // falls back to white (NTKFaceView 0x233ec2ef4/0x233ec03e8).
        // All faces here are transient simulator prototypes; no library opens.
        __attribute__((objc_precise_lifetime)) id controller = ((id (*)(id, SEL, id, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceViewController"), @"alloc"),
            NSSelectorFromString(@"initWithFace:configuration:"), face, nil);
        id view = get(controller, @"faceView");
        if (!family || !view) return;
        id palette = get(get(view, @"faceColorPalette"), @"copy");
        NSMutableArray *sections = NSMutableArray.array;
        for (id collection in get(face, @"editOptionsForCustomEditModes")) {
            if (number(collection, @"collectionType") != 1 || get(collection, @"slot")) continue;
            NSUInteger mode = number(collection, @"mode");
            NSArray *options = get(collection, @"options");
            if (options.count > 512) return;
            NSMutableArray *values = NSMutableArray.array;
            for (id option in options) { @autoreleasepool {
                apply(face, config);
                ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face, NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), option, mode, nil);
                NSDictionary *customization = get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{};
                if (supportOnly) {
                    SEL convert = NSSelectorFromString(@"pigmentEditOption");
                    id native = [option respondsToSelector:convert] ? get(option, @"pigmentEditOption") : option;
                    BOOL declared = [native respondsToSelector:NSSelectorFromString(@"supportsSlider")];
                    [values addObject:@{@"customization": customization,
                        @"optionClass": @(object_getClassName(option)),
                        @"pigmentClass": native ? @(object_getClassName(native)) : NSNull.null,
                        @"supportsSliderDeclared": @(declared),
                        @"supportsSlider": @(flag(native, @"supportsSlider")),
                        @"viewAllowsSlider": @(((BOOL (*)(id, SEL, id))objc_msgSend)(view,
                            NSSelectorFromString(@"allowsEditingSliderEditableColorsForSlot:"), nil))}];
                    continue;
                }
                id color = ((id (*)(id, SEL, id))objc_msgSend)(view, NSSelectorFromString(@"swatchPrimaryColorForColorOption:"), option);
                NSArray *components = rgba(color);
                BOOL slider = flag(option, @"supportsSlider") &&
                    ((BOOL (*)(id, SEL, id))objc_msgSend)(view, NSSelectorFromString(@"allowsEditingSliderEditableColorsForSlot:"), nil);
                BOOL multi = flag(option, @"isMultiColor");
                if (complexOnly && (components || slider)) continue;
                NSMutableDictionary *row = [@{@"customization": customization,
                    @"label": get(option, @"localizedName") ?: @"", @"slider": @(slider),
                    @"visible": @(flag(option, @"isVisible")), @"rgba": components ?: NSNull.null,
                    @"multi": @(multi)} mutableCopy];
                // The original picker supplies both image and primary color
                // for every option (NTKC 0x233d32994/0x233d329d0). Primary
                // Preserve the image separately from the primary color.
                {
                    id image = ((id (*)(id, SEL, id, CGSize))objc_msgSend)(view,
                        NSSelectorFromString(@"swatchImageForColorOption:size:"), option, CGSizeMake(48, 48));
                    NSData *(*png)(id) = dlsym(RTLD_DEFAULT, "UIImagePNGRepresentation");
                    NSData *data = image && png ? png(image) : nil;
                    if (data) {
                        if (data.length > 256 * 1024) { invalidImage = YES; return; }
                        unsigned char digest[CC_SHA256_DIGEST_LENGTH];
                        CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
                        NSMutableString *hash = NSMutableString.string;
                        for (NSUInteger i = 0; i < sizeof(digest); i++) [hash appendFormat:@"%02x", digest[i]];
                        NSString *leaf = [hash stringByAppendingString:@".png"];
                        if (![imageDigests containsObject:hash]) {
                            if (images >= 2048 || ![data writeToFile:[directory stringByAppendingPathComponent:leaf] atomically:YES]) {
                                invalidImage = YES; return;
                            }
                            [imageDigests addObject:hash]; images++;
                        }
                        row[@"image"] = leaf;
                    } else if (!components) { invalidImage = YES; return; }
                }
                if (slider) {
                    NSMutableArray *tokens = NSMutableArray.array;
                    for (NSUInteger percent = 0; percent <= 100; percent++) {
                        id changed = ((id (*)(id, SEL, double))objc_msgSend)(option, NSSelectorFromString(@"copyWithColorFraction:"), percent / 100.0);
                        apply(face, config);
                        ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face, NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), changed, mode, nil);
                        [tokens addObject:get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{}];
                    }
                    row[@"percentTokens"] = tokens;
                    row[@"fullname"] = get(option, @"fullname") ?: @"";
                    NSMutableArray *samples = NSMutableArray.array;
                    for (NSNumber *fraction in @[@0, @0.25, @0.5, @0.75, @1]) {
                        id changed = ((id (*)(id, SEL, double))objc_msgSend)(option, NSSelectorFromString(@"copyWithColorFraction:"), fraction.doubleValue);
                        apply(face, config);
                        ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face, NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), changed, mode, nil);
                        id sampleColor = ((id (*)(id, SEL, id))objc_msgSend)(view, NSSelectorFromString(@"swatchPrimaryColorForColorOption:"), changed);
                        [samples addObject:@{@"fraction": fraction,
                            @"customization": get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{},
                            @"rgba": rgba(sampleColor) ?: NSNull.null}];
                    }
                    row[@"samples"] = samples;
                    row[@"fraction"] = @(((double (*)(id, SEL))objc_msgSend)(option, NSSelectorFromString(@"colorFraction")));
                    id paletteConfig = ((id (*)(id, SEL, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceColorPaletteConfiguration"), @"alloc"),
                        NSSelectorFromString(@"initWithPigmentEditOption:"), option);
                    ((void (*)(id, SEL, id))objc_msgSend)(palette, NSSelectorFromString(@"setConfiguration:"), paletteConfig);
                    NSMutableArray *stops = NSMutableArray.array;
                    for (NSNumber *fraction in @[@0, @0.5, @1]) {
                        id stop = ((id (*)(id, SEL, double))objc_msgSend)(palette, NSSelectorFromString(@"primaryColorWithFraction:"), fraction.doubleValue);
                        [stops addObject:rgba(stop) ?: NSNull.null];
                    }
                    row[@"stops"] = stops;
                }
                [values addObject:row];
            }}
            [sections addObject:@{@"mode": @(mode), @"options": values}];
        }
        if (rows.count >= 256) return;
        [rows addObject:@{@"family": family, @"baseline": baseline[@"customization"] ?: @{},
            @"faceClass": @(object_getClassName(face)), @"viewClass": @(object_getClassName(view)),
            @"paletteClass": palette ? @(object_getClassName(palette)) : NSNull.null,
            @"pigmentEditOption": @(flag(face, @"supportsPigmentEditOption")),
            @"pigmentUI": @(flag(face, @"supportsPigmentUI")), @"sections": sections}];
    } @catch (NSException *error) { fprintf(stderr, "Native pigment unavailable: %s\n", error.reason.UTF8String); }};
    ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
        NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
    if (invalidImage) return 5;
    NSData *data = [NSJSONSerialization dataWithJSONObject:rows options:0 error:nil];
    if (!data || data.length > 32 * 1024 * 1024 || !rows.count) return 4;
    fwrite(data.bytes, 1, data.length, stdout); return 0;
}}
