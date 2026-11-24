#import <UIKit/UIKit.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"
#import "native_face_layer_inspection.h"
#pragma clang diagnostic pop

static IMP originalRenderer;
static NSString *inspectionDirectory, *inspectionKey;
static NSUInteger inspectionCount;
static BOOL inspectionFailed;
static IMP originalTint, originalDrawRect, originalDrawPoint;
static NSMapTable *tintSources;
static NSMutableArray *drawOperations;
static NSMutableSet *imageDigests;
static NSUInteger tintDepth, drawDepth;
static NSDictionary *basisSources;
static NSString *basisWhiteRole;
static NSMutableSet *basisSeen;
static id inspectTint(id image, SEL selector, id color) {
    tintDepth++;
    id actualColor = color;
    if (inspectionKey && basisSources) {
        NSString *leaf = ntkInspectImage(image, inspectionDirectory, imageDigests);
        NSDictionary *binding = leaf ? basisSources[leaf] : nil;
        if (!binding || !ntkSameInspectionColor(ntkInspectUIColor(color), binding[@"rgba"])) {
            inspectionFailed = YES;
        } else {
            [basisSeen addObject:leaf];
            CGFloat channel = [binding[@"role"] isEqual:basisWhiteRole] ? 1 : 0;
            actualColor = [UIColor colorWithRed:channel green:channel blue:channel alpha:[binding[@"rgba"][3] doubleValue]];
        }
    }
    id result = ((id (*)(id, SEL, id))originalTint)(image, selector, actualColor);
    tintDepth--;
    if (inspectionKey && result && color) {
        NSArray *rgba = ntkInspectUIColor(color);
        if (rgba) [tintSources setObject:@{@"source":image, @"rgba":rgba} forKey:result];
        else inspectionFailed = YES;
    }
    return result;
}
static void inspectDraw(UIImage *image, NSString *operation, NSArray *geometry) {
    if (!inspectionKey || tintDepth || drawDepth) return;
    if (drawOperations.count >= 128) { inspectionFailed = YES; return; }
    drawDepth++;
    NSDictionary *tint = [tintSources objectForKey:image];
    UIImage *source = tint[@"source"] ?: image;
    NSString *leaf = ntkInspectImage(source, inspectionDirectory, imageDigests);
    if (!leaf) inspectionFailed = YES;
    else [drawOperations addObject:@{@"operation":operation, @"geometry":geometry,
        @"image":leaf, @"imageSize":@[@(source.size.width), @(source.size.height)],
        @"imageScale":@(source.scale), @"orientation":@(source.imageOrientation),
        @"tint":tint[@"rgba"] ?: NSNull.null}];
    drawDepth--;
}
static void inspectDrawRect(id image, SEL selector, CGRect rect) {
    inspectDraw(image, @"rect", ntkInspectRect(rect));
    drawDepth++;
    ((void (*)(id, SEL, CGRect))originalDrawRect)(image, selector, rect);
    drawDepth--;
}
static void inspectDrawPoint(id image, SEL selector, CGPoint point) {
    inspectDraw(image, @"point", @[@(point.x), @(point.y)]);
    drawDepth++;
    ((void (*)(id, SEL, CGPoint))originalDrawPoint)(image, selector, point);
    drawDepth--;
}
static BOOL instanceABI(Method method, const char *result, const char *argument) {
    NSMethodSignature *signature = method ? [NSMethodSignature signatureWithObjCTypes:method_getTypeEncoding(method)] : nil;
    return signature && signature.numberOfArguments == 3 && !strcmp(signature.methodReturnType, result) &&
        !strcmp([signature getArgumentTypeAtIndex:2], argument);
}
static id inspectRender(id type, SEL selector, id view, CGSize size, id device, id key, NSUInteger method) {
    NSString *captureKey = inspectionKey ? [inspectionKey stringByAppendingFormat:@"-render-%lu", (unsigned long)inspectionCount++] : nil;
    if (captureKey && !ntkInspectSnapshotLayers(view, inspectionDirectory,
            [captureKey stringByAppendingString:@"-before"])) inspectionFailed = YES;
    id result = ((id (*)(id, SEL, id, CGSize, id, id, NSUInteger))originalRenderer)(type, selector, view, size, device, key, method);
    if (captureKey && !ntkInspectSnapshotLayers(view, inspectionDirectory,
            [captureKey stringByAppendingString:@"-after"])) inspectionFailed = YES;
    return result;
}

static BOOL installInspection(void) {
    // Hook only this short-lived research process. Verify the native ABI before
    // inspecting the view Apple passes to its actual swatch renderer.
    Method method = class_getClassMethod(NSClassFromString(@"NTKSwatchRenderer"),
        NSSelectorFromString(@"renderSwatchForView:size:device:key:method:"));
    NSMethodSignature *signature = method ? [NSMethodSignature signatureWithObjCTypes:method_getTypeEncoding(method)] : nil;
    if (!signature || signature.numberOfArguments != 7 || strcmp(signature.methodReturnType, @encode(id))) return NO;
    const char *arguments[] = {@encode(id), @encode(CGSize), @encode(id), @encode(id), @encode(NSUInteger)};
    for (NSUInteger index = 0; index < 5; index++)
        if (strcmp([signature getArgumentTypeAtIndex:index + 2], arguments[index])) return NO;
    Method tint = class_getInstanceMethod(UIImage.class, NSSelectorFromString(@"_flatImageWithColor:"));
    Method rect = class_getInstanceMethod(UIImage.class, @selector(drawInRect:));
    Method point = class_getInstanceMethod(UIImage.class, @selector(drawAtPoint:));
    if (!instanceABI(tint, @encode(id), @encode(id)) ||
        !instanceABI(rect, @encode(void), @encode(CGRect)) ||
        !instanceABI(point, @encode(void), @encode(CGPoint))) return NO;
    tintSources = NSMapTable.weakToStrongObjectsMapTable;
    imageDigests = NSMutableSet.set;
    originalRenderer = method_setImplementation(method, (IMP)inspectRender);
    originalTint = method_setImplementation(tint, (IMP)inspectTint);
    originalDrawRect = method_setImplementation(rect, (IMP)inspectDrawRect);
    originalDrawPoint = method_setImplementation(point, (IMP)inspectDrawPoint);
    return originalRenderer && originalTint && originalDrawRect && originalDrawPoint;
}

// Simulator-only research captures of the actual editor swatch provider.
// Transient prototypes only: no library, preferences, pairing or transport.
static id get(id object, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static NSUInteger number(id object, NSString *name) {
    return ((NSUInteger (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static int captureSwatches(int argc, const char *argv[]) { @autoreleasepool {
    inspectionFailed = NO;
    basisSources = nil; basisWhiteRole = nil; basisSeen = nil;
    if (argc < 3 || argc > 7 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    NSString *directory = @(argv[1]), *requested = @(argv[2]);
    NSString *color = nil, *basisPath = nil;
    BOOL inspect = NO;
    for (int index = 3; index < argc; index++) {
        if (!strcmp(argv[index], "--inspect")) inspect = YES;
        else if (!strcmp(argv[index], "--basis") && index + 1 < argc && !basisPath) {
            basisPath = @(argv[++index]); inspect = YES;
        } else if (!color && argv[index][0] != '-') color = @(argv[index]);
        else return 2;
    }
    if (basisPath) {
        NSData *data = [NSData dataWithContentsOfFile:basisPath];
        id basis = data.length && data.length <= 128 * 1024 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
        if (![basis isKindOfClass:NSDictionary.class] || ![basis[@"family"] isEqual:requested] ||
            ![basis[@"sources"] isKindOfClass:NSDictionary.class] || [basis[@"sources"] count] < 1 || [basis[@"sources"] count] > 128 ||
            ![basis[@"whiteRole"] isKindOfClass:NSString.class]) return 2;
        basisSources = basis[@"sources"]; basisWhiteRole = basis[@"whiteRole"];
        NSMutableSet *roles = NSMutableSet.set;
        NSRegularExpression *sourcePattern = [NSRegularExpression regularExpressionWithPattern:@"^[a-f0-9]{64}\\.png$" options:0 error:nil];
        NSRegularExpression *rolePattern = [NSRegularExpression regularExpressionWithPattern:@"^g_[a-f0-9]{16}$" options:0 error:nil];
        for (id source in basisSources) {
            id binding = basisSources[source];
            if (![source isKindOfClass:NSString.class] || ![binding isKindOfClass:NSDictionary.class] ||
                ![sourcePattern numberOfMatchesInString:source options:0 range:NSMakeRange(0, [source length])] ||
                ![binding[@"role"] isKindOfClass:NSString.class] ||
                ![rolePattern numberOfMatchesInString:binding[@"role"] options:0 range:NSMakeRange(0, [binding[@"role"] length])] ||
                ![binding[@"rgba"] isKindOfClass:NSArray.class] || [binding[@"rgba"] count] != 4) return 2;
            for (id value in binding[@"rgba"])
                if (![value isKindOfClass:NSNumber.class] || !isfinite([value doubleValue]) ||
                    [value doubleValue] < 0 || [value doubleValue] > 1.000001) return 2;
            [roles addObject:binding[@"role"]];
        }
        if (roles.count > 4) return 2;
        if (![basisWhiteRole isEqual:@"black"] && ![roles containsObject:basisWhiteRole]) return 2;
        basisSeen = NSMutableSet.set;
    }
    if (![requested hasPrefix:@"type:"] && ![requested hasPrefix:@"bundle:"]) return 2;
    if ([NSFileManager.defaultManager fileExistsAtPath:directory] ||
        ![NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil]) return 2;
    [NSUserDefaults.standardUserDefaults setVolatileDomain:@{
        @"AppleLanguages": @[@"en-US"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW) ||
        !dlopen("/System/Library/PrivateFrameworks/NanoTimeKitCompanion.framework/NanoTimeKitCompanion", RTLD_NOW)) return 3;
    if (inspect && !originalRenderer && !installInspection()) return 5;
    if (inspect) {
        imageDigests = NSMutableSet.set;
        tintSources = NSMapTable.weakToStrongObjectsMapTable;
    }
    inspectionDirectory = directory;
    id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
    NSMutableArray *rows = NSMutableArray.array;
    __block BOOL found = NO;
    void (^observe)(id) = ^(id bundle) { @try {
        id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle,
            NSSelectorFromString(@"defaultFaceForDevice:"), device);
        NSDictionary *initial = face ? get(face, @"JSONObjectRepresentation") : nil;
        NSString *family = initial[@"bundle id"] ? [@"bundle:" stringByAppendingString:initial[@"bundle id"]]
            : initial[@"face type"] ? [@"type:" stringByAppendingString:initial[@"face type"]] : nil;
        if (![family isEqual:requested] || found || initial[@"customData"] ||
            initial[@"resource directory"] || get(face, @"resourceDirectory")) return;
        for (NSString *slot in get(face, @"_complicationSlotDescriptors"))
            ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
        id config = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
            NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"),
            get(face, @"JSONObjectRepresentation"), face, device);
        ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), config);
        NSArray *collections = get(face, @"editOptionsForCustomEditModes");
        if (color) {
            NSString *base = color;
            NSNumber *fraction = nil;
            NSRange colon = [color rangeOfString:@":" options:NSBackwardsSearch];
            if (colon.location != NSNotFound) {
                base = [color substringToIndex:colon.location];
                NSString *suffix = [color substringFromIndex:colon.location + 1];
                NSScanner *scanner = [NSScanner scannerWithString:suffix];
                double value;
                if (![scanner scanDouble:&value] || !scanner.isAtEnd || !isfinite(value) || value < 0 || value > 1) return;
                fraction = @(value);
            }
            BOOL selected = NO;
            for (id collection in collections) {
                if (number(collection, @"collectionType") != 1 || get(collection, @"slot")) continue;
                for (id option in get(collection, @"options")) {
                    BOOL matches = [option respondsToSelector:NSSelectorFromString(@"fullname")] && [get(option, @"fullname") isEqual:base];
                    if (!matches) {
                        // Legacy pigments serialize aliases rather than their
                        // fullname. Obtain the identity from the native model.
                        ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), config);
                        ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face,
                            NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), option, number(collection, @"mode"), nil);
                        matches = [get(face, @"JSONObjectRepresentation")[@"customization"][@"color"] isEqual:base];
                    }
                    if (!matches) continue;
                    id candidate = option;
                    if (fraction) {
                        SEL supports = NSSelectorFromString(@"supportsSlider");
                        if (![option respondsToSelector:supports] || !((BOOL (*)(id, SEL))objc_msgSend)(option, supports)) return;
                        SEL selector = NSSelectorFromString(@"copyWithColorFraction:");
                        if (![option respondsToSelector:selector]) return;
                        candidate = ((id (*)(id, SEL, double))objc_msgSend)(option, selector, fraction.doubleValue);
                    }
                    ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face,
                        NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), candidate, number(collection, @"mode"), nil);
                    selected = YES; break;
                }
            }
            if (!selected) return;
        }
        NSDictionary *baseline = get(face, @"JSONObjectRepresentation");
        id baselineConfig = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
            NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), baseline, face, device);
        NSMutableDictionary *candidateContexts = NSMutableDictionary.dictionary;
        for (id collection in collections) {
            if (number(collection, @"collectionType") != 0 || get(collection, @"slot")) continue;
            NSUInteger mode = number(collection, @"mode"), index = 0;
            NSArray *options = get(collection, @"options");
            if (options.count > 512) return;
            for (id option in options) {
                ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), baselineConfig);
                ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face,
                    NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), option, mode, nil);
                candidateContexts[[NSString stringWithFormat:@"%lu-%lu", (unsigned long)mode, (unsigned long)index++]] =
                    get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{};
            }
        }
        ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), baselineConfig);
        __attribute__((objc_precise_lifetime)) id controller = ((id (*)(id, SEL, id, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceViewController"), @"alloc"),
            NSSelectorFromString(@"initWithFace:configuration:"), face, nil);
        id view = get(controller, @"faceView");
        NSDictionary *selected = get(face, @"selectedOptionsForCustomEditModes");
        id pigment = selected[@10];
        BOOL slider = [pigment respondsToSelector:NSSelectorFromString(@"supportsSlider")] &&
            ((BOOL (*)(id, SEL))objc_msgSend)(pigment, NSSelectorFromString(@"supportsSlider"));
        id palette = get(view, @"faceColorPalette");
        NSArray *primary = ntkInspectUIColor(get(palette, @"primaryColor"));
        BOOL white = ((BOOL (*)(id, SEL))objc_msgSend)(palette, NSSelectorFromString(@"isWhiteColor"));
        if (!primary) return;
        for (id collection in collections) {
            if (number(collection, @"collectionType") != 0 || get(collection, @"slot")) continue;
            NSUInteger mode = number(collection, @"mode"), index = 0;
            NSArray *options = get(collection, @"options");
            if (options.count > 512) return;
            for (id option in options) {
                inspectionKey = inspect ? [NSString stringWithFormat:@"%lu-%lu", (unsigned long)mode, (unsigned long)index] : nil;
                inspectionCount = 0;
                drawOperations = NSMutableArray.array;
                UIImage *image = ((id (*)(id, SEL, id, NSUInteger, id, id))objc_msgSend)(view,
                    NSSelectorFromString(@"swatchImageForEditOption:mode:withSelectedOptions:refreshHandler:"), option, mode, selected, nil);
                inspectionKey = nil;
                if (image && (image.size.width > 256 || image.size.height > 256)) return;
                NSData *data = image ? UIImagePNGRepresentation(image) : nil;
                NSString *identity = [NSString stringWithFormat:@"%lu-%lu", (unsigned long)mode, (unsigned long)index++];
                NSString *leaf = [identity stringByAppendingString:@".png"];
                if (data && (data.length > 1024 * 1024 || ![data writeToFile:[directory stringByAppendingPathComponent:leaf] atomically:YES])) return;
                [rows addObject:@{@"mode":@(mode), @"label":get(option, @"localizedName") ?: @"",
                    @"customization":candidateContexts[identity],
                    @"optionClass":NSStringFromClass([option class]), @"image":data ? leaf : NSNull.null,
                    @"nativeRenderCalls":@(inspectionCount),
                    @"drawOperations":drawOperations,
                    @"size":@[@(image.size.width), @(image.size.height)], @"scale":@(image.scale)}];
            }
        }
        found = YES;
        NSData *data = [NSJSONSerialization dataWithJSONObject:@{@"family":family,
            @"customization":get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{}, @"rows":rows,
            @"palettePrimaryColor":primary, @"paletteIsWhiteColor":@(white), @"supportsSlider":@(slider)}
            options:NSJSONWritingPrettyPrinted error:nil];
        if (!data || ![data writeToFile:[directory stringByAppendingPathComponent:@"capture.json"] atomically:YES]) found = NO;
    } @catch (NSException *error) {
        fprintf(stderr, "%s: %s\n", error.name.UTF8String, error.reason.UTF8String);
    }};
    ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
        NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
    if (basisSources && ![basisSeen isEqual:[NSSet setWithArray:basisSources.allKeys]]) inspectionFailed = YES;
    return inspectionFailed ? 6 : found ? 0 : 4;
} }

int main(int argc, const char *argv[]) { @autoreleasepool {
    BOOL sampleShades = argc == 6 && !strcmp(argv[5], "--sample-shades");
    if ((argc != 5 && !sampleShades) || strcmp(argv[3], "--colors-file")) return captureSwatches(argc, argv);
    if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    NSData *input = [NSData dataWithContentsOfFile:@(argv[4])];
    id colors = input.length && input.length <= 128 * 1024
        ? [NSJSONSerialization JSONObjectWithData:input options:0 error:nil] : nil;
    if (![colors isKindOfClass:NSArray.class] || [colors count] < 1 || [colors count] > 2048) return 2;
    NSMutableSet *seen = NSMutableSet.set;
    for (id color in colors) {
        if (![color isKindOfClass:NSString.class] || [color length] < 1 || [color length] > 128 || [seen containsObject:color]) return 2;
        [seen addObject:color];
    }
    NSString *directory = @(argv[1]);
    if ([NSFileManager.defaultManager fileExistsAtPath:directory] ||
        ![NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil]) return 2;
    NSMutableArray *index = NSMutableArray.array;
    NSUInteger ordinal = 0;
    for (NSString *color in colors) { @autoreleasepool {
        NSString *leaf = [NSString stringWithFormat:@"context-%lu", (unsigned long)ordinal++];
        NSString *output = [directory stringByAppendingPathComponent:leaf];
        const char *arguments[] = {argv[0], output.UTF8String, argv[2], color.UTF8String, "--inspect"};
        int result = captureSwatches(5, arguments);
        if (result) return result;
        [index addObject:@{@"directory":leaf, @"color":color}];
        if (sampleShades && [color rangeOfString:@":"].location == NSNotFound) {
            NSData *captureData = [NSData dataWithContentsOfFile:[output stringByAppendingPathComponent:@"capture.json"]];
            id capture = captureData ? [NSJSONSerialization JSONObjectWithData:captureData options:0 error:nil] : nil;
            if (![capture isKindOfClass:NSDictionary.class]) return 4;
            if ([capture[@"supportsSlider"] boolValue]) {
                for (NSNumber *percent in @[@0, @25, @75, @100]) {
                    NSString *shade = [color stringByAppendingFormat:@":%.2f", percent.doubleValue / 100];
                    NSString *shadeLeaf = [NSString stringWithFormat:@"context-%lu", (unsigned long)ordinal++];
                    NSString *shadeOutput = [directory stringByAppendingPathComponent:shadeLeaf];
                    const char *shadeArguments[] = {argv[0], shadeOutput.UTF8String, argv[2], shade.UTF8String, "--inspect"};
                    int shadeResult = captureSwatches(5, shadeArguments);
                    if (shadeResult) return shadeResult;
                    [index addObject:@{@"directory":shadeLeaf, @"color":shade}];
                }
            }
        }
    }}
    NSData *data = [NSJSONSerialization dataWithJSONObject:index options:NSJSONWritingPrettyPrinted error:nil];
    return data && [data writeToFile:[directory stringByAppendingPathComponent:@"index.json"] atomically:YES] ? 0 : 4;
} }
