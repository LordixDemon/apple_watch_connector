#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <CoreGraphics/CoreGraphics.h>
#import <dlfcn.h>
#import "native_face_layer_inspection.h"

// Build-time renderer only. No paired device, Replicator or Watch collection API.
static id get(id object, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
// Watch7,5 / 23S303 implements this class method by returning imageWithName:
// directly. The simulator adds unconditional CGContext white guide strokes.
// Use the device behavior only in the explicit local build-time research mode.
static id collieDeviceMask(id object, SEL selector, id name, CGSize size) {
    (void)object; (void)selector; (void)size;
    return ((id (*)(id, SEL, id))objc_msgSend)(NSClassFromString(@"NTKCollieFaceBundle"),
        NSSelectorFromString(@"imageWithName:"), name);
}
static void inspectImages(id view, NSString *output, NSString *key, NSUInteger *count) {
    if (!view || *count >= 64) return;
    if ([view respondsToSelector:NSSelectorFromString(@"image")]) {
        id image = get(view, @"image");
        NSData *(*png)(id) = dlsym(RTLD_DEFAULT, "UIImagePNGRepresentation");
        NSData *data = [image isKindOfClass:NSClassFromString(@"UIImage")] && png ? png(image) : nil;
        if (data.length && data.length <= 4 * 1024 * 1024) {
            NSString *name = [NSString stringWithFormat:@"%@-image-%lu.png", key, (*count)++];
            [data writeToFile:[output stringByAppendingPathComponent:name] atomically:YES];
        }
    }
    for (id child in get(view, @"subviews")) inspectImages(child, output, key, count);
}
int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc == 2 && strcmp(argv[1], "--configure-english-locale") == 0) {
            if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
            NSMutableDictionary *global = [[NSUserDefaults.standardUserDefaults
                persistentDomainForName:NSGlobalDomain] mutableCopy] ?: NSMutableDictionary.dictionary;
            global[@"AppleLanguages"] = @[@"en-US"];
            global[@"AppleLocale"] = @"en_US";
            [NSUserDefaults.standardUserDefaults setPersistentDomain:global forName:NSGlobalDomain];
            return [NSUserDefaults.standardUserDefaults synchronize] ? 0 : 2;
        }
        if (argc == 3 && strcmp(argv[1], "--restore-locale") == 0) {
            if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
            NSData *data = [NSData dataWithContentsOfFile:@(argv[2])];
            NSDictionary *original = data.length <= 65536 ? [NSPropertyListSerialization
                propertyListWithData:data options:0 format:NULL error:nil] : nil;
            if (![original isKindOfClass:NSDictionary.class] ||
                    ![original[@"AppleLanguages"] isKindOfClass:NSArray.class] ||
                    ![original[@"AppleLocale"] isKindOfClass:NSString.class]) return 2;
            NSMutableDictionary *global = [[NSUserDefaults.standardUserDefaults
                persistentDomainForName:NSGlobalDomain] mutableCopy] ?: NSMutableDictionary.dictionary;
            global[@"AppleLanguages"] = original[@"AppleLanguages"];
            global[@"AppleLocale"] = original[@"AppleLocale"];
            [NSUserDefaults.standardUserDefaults setPersistentDomain:global forName:NSGlobalDomain];
            return [NSUserDefaults.standardUserDefaults synchronize] ? 0 : 2;
        }
        if (argc == 2 && strcmp(argv[1], "--options") == 0) {
            if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
            NSDictionary *options = get(NSClassFromString(@"NTKFaceSnapshotter"), @"defaultModernSnapshotOptions");
            for (NSString *key in options) {
                id value = options[key];
                fprintf(stderr, "%s type=%s numeric=%s\n", key.UTF8String,
                    NSStringFromClass([value class]).UTF8String,
                    [value isKindOfClass:NSNumber.class] ? [value description].UTF8String : "none");
            }
            return 0;
        }
        BOOL uiOnly = argc == 4 && strcmp(argv[3], "--ui-only") == 0;
        BOOL components = argc == 4 && strcmp(argv[3], "--components") == 0;
        BOOL inspect = components || (argc == 4 && strcmp(argv[3], "--inspect") == 0);
        BOOL deviceMasks = argc == 4 && strcmp(argv[3], "--local-device-masks") == 0;
        BOOL local = inspect || deviceMasks || (argc == 4 && strcmp(argv[3], "--local") == 0);
        if (argc != 3 && !uiOnly && !local) return 2;
        NSData *input = [NSData dataWithContentsOfFile:@(argv[1])];
        NSArray *rows = input.length <= 16 * 1024 * 1024
            ? [NSJSONSerialization JSONObjectWithData:input options:0 error:nil] : nil;
        if (![rows isKindOfClass:NSArray.class] || rows.count > 10000) return 2;
        NSString *output = [@(argv[2]) stringByStandardizingPath];
        if (![output hasPrefix:@"/tmp/"] && ![output hasPrefix:@"/private/tmp/"]) return 2;
        if (![[NSFileManager defaultManager] createDirectoryAtPath:output
                withIntermediateDirectories:YES attributes:nil error:nil]) return 2;
        // Catalog locale is explicit; never inherit the developer's process language.
        [NSUserDefaults.standardUserDefaults setVolatileDomain:@{
            @"AppleLanguages": @[@"en"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
        NSMutableDictionary *bundles = NSMutableDictionary.dictionary;
        void (^observe)(id) = ^(id bundle) {
            id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle,
                NSSelectorFromString(@"defaultFaceForDevice:"), device);
            NSDictionary *json = face ? get(face, @"JSONObjectRepresentation") : nil;
            NSString *family = json[@"bundle id"] ? [@"bundle:" stringByAppendingString:json[@"bundle id"]]
                : json[@"face type"] ? [@"type:" stringByAppendingString:json[@"face type"]] : nil;
            if (family) bundles[family] = bundle;
        };
        ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
            NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
        if (deviceMasks) {
            if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 8;
            Method method = class_getClassMethod(NSClassFromString(@"NTKCollieFaceView"),
                NSSelectorFromString(@"_backgroundMaskFromImageNamed:size:"));
            NSMethodSignature *signature = method ? [NSMethodSignature
                signatureWithObjCTypes:method_getTypeEncoding(method)] : nil;
            if (!signature || signature.numberOfArguments != 4 ||
                    strcmp(signature.methodReturnType, @encode(id)) ||
                    strcmp([signature getArgumentTypeAtIndex:2], @encode(id)) ||
                    strcmp([signature getArgumentTypeAtIndex:3], @encode(CGSize))) return 8;
            // This standalone process terminates after generation. No dylib,
            // persisted preference, XPC service or physical Watch is changed.
            method_setImplementation(method, (IMP)collieDeviceMask);
        }
        // Both paths use Apple's actual snapshot implementation. The local
        // research process has its own native queue instead of serializing all
        // catalog generation through the simulator's single XPC snapshot service.
        // Its native RGBA may differ in rasterization; existing assets stay exact.
        id client = local
            ? get(get(NSClassFromString(@"NTKFaceSnapshotter"), @"alloc"), @"init")
            : get(NSClassFromString(@"NTKFaceSnapshotClient"), @"sharedInstance");
        NSMutableDictionary *options = [get(NSClassFromString(@"NTKFaceSnapshotter"), @"defaultModernSnapshotOptions") mutableCopy];
        if (uiOnly) options[@"NTKSnapshotUIOnlyKey"] = @YES;
        NSRegularExpression *hex = [NSRegularExpression regularExpressionWithPattern:@"^[a-f0-9]{64}$" options:0 error:nil];
        NSUInteger done = 0, failed = 0;
        NSMutableArray *rejections = NSMutableArray.array;
        for (NSDictionary *row in rows) {
            @autoreleasepool {
                NSDictionary *requested = row[@"configuration"];
                NSString *key = row[@"key"], *family = row[@"family"];
                if (![requested isKindOfClass:NSDictionary.class] || ![key isKindOfClass:NSString.class]
                        || ![hex numberOfMatchesInString:key options:0 range:NSMakeRange(0, key.length)]
                        || requested[@"customData"] || requested[@"resource directory"]
                        || requested[@"complications"] || (requested[@"customization"] &&
                            ![requested[@"customization"] isKindOfClass:NSDictionary.class])) return 4;
                NSString *requestedFamily = requested[@"bundle id"]
                    ? [@"bundle:" stringByAppendingString:requested[@"bundle id"]]
                    : requested[@"face type"] ? [@"type:" stringByAppendingString:requested[@"face type"]] : nil;
                if (![family isEqual:requestedFamily]) return 4;
                id face = ((id (*)(id, SEL, id))objc_msgSend)(bundles[family],
                    NSSelectorFromString(@"defaultFaceForDevice:"), device);
                if (!face || get(face, @"resourceDirectory")) {
                    [rejections addObject:@{@"key": key, @"family": family,
                        @"reason": face ? @"resource-required" : @"native-face-unavailable"}];
                    failed++; continue;
                }
                for (NSString *slot in get(face, @"_complicationSlotDescriptors")) {
                    ((void (*)(id, SEL, id, id))objc_msgSend)(face,
                        NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
                }
                id config = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
                    get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
                    NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), requested, face, device);
                ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), config);
                NSDictionary *actual = get(face, @"JSONObjectRepresentation");
                if (![(actual[@"customization"] ?: @{}) isEqual:(requested[@"customization"] ?: @{})]
                        || [actual[@"complications"] count]) {
                    [rejections addObject:@{@"key": key, @"family": family,
                        @"reason": @"native-normalization",
                        @"requestedCustomization": requested[@"customization"] ?: @{},
                        @"actualCustomization": actual[@"customization"] ?: @{},
                        @"actualComplicationSlots": [actual[@"complications"] allKeys] ?: @[]}];
                    failed++; continue;
                }
                __block BOOL finished = NO, saved = NO;
                void (^completion)(id, id) = ^(id result, id error) {
                    id image = [result respondsToSelector:NSSelectorFromString(@"snapshot")]
                        ? get(result, @"snapshot") : result;
                    NSData *(*png)(id) = dlsym(RTLD_DEFAULT, "UIImagePNGRepresentation");
                    NSData *data = !error && [image isKindOfClass:NSClassFromString(@"UIImage")] && png ? png(image) : nil;
                    if (data.length && data.length <= 4 * 1024 * 1024) {
                        saved = [data writeToFile:[output stringByAppendingPathComponent:
                            [key stringByAppendingString:@".png"]] atomically:YES];
                    }
                    finished = YES;
                };
                ((void (*)(id, SEL, id, id, id))objc_msgSend)(client,
                    NSSelectorFromString(local ? @"requestSnapshotOfFace:options:completion:"
                        : @"snapshotFace:options:completion:"), face, options, completion);
                NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:25];
                BOOL inspected = NO;
                while (!finished && deadline.timeIntervalSinceNow > 0) {
                    [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
                    if (inspect && !inspected) {
                        // Read the local snapshotter's retained hierarchy only.
                        // Export existing UIImage pixels without changing them.
                        id window = [client valueForKey:@"_snapshotWindow"];
                        NSString *hierarchy = get(window, @"recursiveDescription");
                        if (hierarchy.length <= 1024 * 1024 && hierarchy.length) inspected =
                            [hierarchy writeToFile:[output stringByAppendingPathComponent:
                            [key stringByAppendingString:@"-hierarchy.txt"]]
                            atomically:YES encoding:NSUTF8StringEncoding error:nil];
                        if (inspected) {
                            NSUInteger count = 0;
                            inspectImages(window, output, key, &count);
                            if (!ntkInspectSnapshotLayers(window, output, key)) return 9;
                            if (components && !ntkApplyComponentBasis(window,
                                    row[@"componentBindings"], row[@"whiteRole"])) return 11;
                        }
                    }
                }
                // A timed-out callback must never outlive this stack's blocks.
                if (!finished) return 5;
                if (saved) done++; else {
                    failed++;
                    [rejections addObject:@{@"key": key, @"family": family,
                        @"reason": @"snapshot-unavailable"}];
                }
                if ((done + failed) % 50 == 0) fprintf(stderr, "Rendered=%lu rejected=%lu\n", done, failed);
            }
        }
        NSDictionary *report = @{@"requested": @(rows.count), @"rendered": @(done),
            @"rejected": @(failed), @"rejections": rejections};
        if (deviceMasks) {
            NSMutableDictionary *annotated = [report mutableCopy];
            annotated[@"maskPolicy"] = @"Watch7,5-23S303-Collie-imageWithName";
            report = annotated;
        }
        NSData *reportData = [NSJSONSerialization dataWithJSONObject:report options:0 error:nil];
        NSString *reportName = [[@(argv[1]).lastPathComponent stringByDeletingPathExtension]
            stringByAppendingString:@"-report.json"];
        if (!reportData.length || reportData.length > 16 * 1024 * 1024
                || ![reportData writeToFile:[output stringByAppendingPathComponent:reportName] atomically:YES]) return 7;
        fprintf(stderr, "Rendered=%lu rejected=%lu total=%lu\n", done, failed, rows.count);
        return failed ? 6 : 0;
    }
}
