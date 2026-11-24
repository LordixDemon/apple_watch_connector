#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>

// Simulator research only. Export a transient face through Apple's actual
// Greenfield sharing encoder. Never accesses a library, pairing or IDS transport.
static id value(id object, NSString *selector) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(selector));
}
static id argument(id object, NSString *selector, id arg) {
    return ((id (*)(id, SEL, id))objc_msgSend)(object, NSSelectorFromString(selector), arg);
}
static void assign(id object, NSString *selector, id arg) {
    ((void (*)(id, SEL, id))objc_msgSend)(object, NSSelectorFromString(selector), arg);
}
static NSString *family(NSDictionary *json) {
    if ([json[@"bundle id"] isKindOfClass:NSString.class]) return [@"bundle:" stringByAppendingString:json[@"bundle id"]];
    if ([json[@"face type"] isKindOfClass:NSString.class]) return [@"type:" stringByAppendingString:json[@"face type"]];
    return nil;
}
int main(int argc, char **argv) {
    @autoreleasepool { @try {
        BOOL decode = argc == 4 && [@(argv[1]) isEqual:@"--decode"];
        if (argc != 4 || (!decode && ![@(argv[1]) isEqual:@"--configuration"])) {
            fprintf(stderr, "usage: probe-native-face-sharing --configuration face.json new-output-directory | --decode native.watchface new-output-directory\n");
            return 2;
        }
        NSData *input = [NSData dataWithContentsOfFile:@(argv[2])];
        NSError *error = nil;
        NSDictionary *requested = !decode && input.length && input.length <= 131072
            ? [NSJSONSerialization JSONObjectWithData:input options:0 error:&error] : nil;
        if (!decode && (![requested isKindOfClass:NSDictionary.class] || !family(requested)
                || ![requested[@"customization"] isKindOfClass:NSDictionary.class])) return 3;
        if (decode && (!input.length || input.length > 16 * 1024 * 1024)) return 3;
        NSString *destination = [@(argv[3]) stringByStandardizingPath];
        if (![destination hasPrefix:@"/tmp/"] && ![destination hasPrefix:@"/private/tmp/"]) return 4;
        NSFileManager *files = NSFileManager.defaultManager;
        if ([files fileExistsAtPath:destination]) return 4;
        if (![files createDirectoryAtPath:destination withIntermediateDirectories:YES attributes:nil error:&error]) return 4;
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 5;
        if (decode) {
            __block BOOL finished = NO, saved = NO;
            void (^completion)(id, id) = ^(id decoded, id failure) {
                id face = value(decoded, @"watchFace");
                NSDictionary *json = face ? value(face, @"JSONObjectRepresentation") : nil;
                NSData *data = json ? [NSJSONSerialization dataWithJSONObject:json options:NSJSONWritingPrettyPrinted error:nil] : nil;
                if (data) [data writeToFile:[destination stringByAppendingPathComponent:@"wire-face.json"] atomically:YES];
                BOOL (*zip)(id, id, id) = dlsym(RTLD_DEFAULT, "NTKDZipFileFromFace");
                saved = face && zip && zip(face, nil, [destination stringByAppendingPathComponent:@"wire.watchface"]);
                NSDictionary *report = @{@"decoded": @(face != nil), @"wireSaved": @(saved),
                    @"error": [failure description] ?: @"", @"family": family(json) ?: @""};
                NSData *reportData = [NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted error:nil];
                fwrite(reportData.bytes, 1, reportData.length, stdout); fputc('\n', stdout); fflush(stdout);
                finished = YES;
            };
            ((void (*)(id, SEL, id, id))objc_msgSend)(NSClassFromString(@"NTKGreenfieldUtilities"),
                NSSelectorFromString(@"decodeWatchFaceFromUrl:completionBlock:"), [NSURL fileURLWithPath:@(argv[2])], completion);
            NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:45];
            while (!finished && deadline.timeIntervalSinceNow > 0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.1]];
            if (!finished) fprintf(stderr, "Native sharing decoder timed out\n");
            return saved ? 0 : 7;
        }
        id device = value(NSClassFromString(@"CLKDevice"), @"currentDevice");
        id manager = value(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager");
        __block id face = nil;
        void (^observe)(id) = ^(id bundle) {
            id candidate = argument(bundle, @"defaultFaceForDevice:", device);
            if (!candidate || ![family(value(candidate, @"JSONObjectRepresentation")) isEqual:family(requested)]) return;
            for (NSString *slot in value(candidate, @"_complicationSlotDescriptors")) {
                ((void (*)(id, SEL, id, id))objc_msgSend)(candidate,
                    NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
            }
            id configuration = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
                value(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
                NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), requested, candidate, device);
            assign(candidate, @"applyConfiguration:", configuration);
            NSDictionary *actual = value(candidate, @"JSONObjectRepresentation");
            if (![actual[@"customization"] isEqual:requested[@"customization"]]
                    || ![(actual[@"complications"] ?: @{}) isEqual:(requested[@"complications"] ?: @{})]) {
                fprintf(stderr, "Requested configuration normalized: customizationSame=%d complicationsSame=%d\n",
                    [actual[@"customization"] isEqual:requested[@"customization"]],
                    [(actual[@"complications"] ?: @{}) isEqual:(requested[@"complications"] ?: @{})]);
                return;
            }
            NSString *resources = requested[@"resource directory"];
            if (resources) {
                BOOL directory = NO;
                if (![resources isKindOfClass:NSString.class] || ![files fileExistsAtPath:resources isDirectory:&directory] || !directory) return;
                assign(candidate, @"setResourceDirectory:", resources);
            }
            face = candidate;
        };
        ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(manager,
            NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
        if (!face && requested[@"bundle id"]) {
            id bundle = ((id (*)(id, SEL, id, id))objc_msgSend)(manager,
                NSSelectorFromString(@"faceBundleForBundleIdentifier:onDevice:"), requested[@"bundle id"], device);
            if (bundle) observe(bundle);
        }
        if (!face && [requested[@"bundle id"] hasPrefix:@"com.apple.NTK"]) {
            // Some resource builders are omitted from the resource-free gallery.
            // Load only the exact installed native bundle requested by the fixture.
            NSString *name = [requested[@"bundle id"] componentsSeparatedByString:@"."].lastObject;
            NSCharacterSet *letters = [NSCharacterSet alphanumericCharacterSet];
            if ([name rangeOfCharacterFromSet:letters.invertedSet].location == NSNotFound) {
                NSString *path = [@"/System/Library/NanoTimeKit/FaceBundles" stringByAppendingPathComponent:[name stringByAppendingString:@".bundle"]];
                NSString *executable = [path stringByAppendingPathComponent:name];
                if (dlopen(executable.UTF8String, RTLD_NOW)) {
                    Class cls = NSClassFromString(name);
                    if ([cls instancesRespondToSelector:NSSelectorFromString(@"defaultFaceForDevice:")]) observe(value(cls, @"new"));
                }
            }
        }
        if (!face) { fprintf(stderr, "Requested family/configuration unavailable; no substitute exported\n"); return 6; }
        id recipe = argument(value(NSClassFromString(@"NTKGreenfieldDraftRecipe"), @"alloc"), @"initWithFace:", face);
        __block BOOL finished = NO, saved = NO;
        void (^completion)(id, id) = ^(id encoded, id failure) {
            NSError *copyError = nil;
            NSURL *url = value(encoded, @"watchFaceDataUrl");
            NSDictionary *attributes = url.isFileURL ? [files attributesOfItemAtPath:url.path error:nil] : nil;
            if ([attributes[NSFileType] isEqual:NSFileTypeRegular]
                    && [attributes[NSFileSize] unsignedLongLongValue] <= 16 * 1024 * 1024) {
                saved = [files copyItemAtURL:url toURL:[NSURL fileURLWithPath:[destination stringByAppendingPathComponent:@"native.watchface"]] error:&copyError];
            }
            id image = value(encoded, @"watchFaceImage");
            NSData *(*png)(id) = dlsym(RTLD_DEFAULT, "UIImagePNGRepresentation");
            NSData *pixels = image && png ? png(image) : nil;
            if (pixels) [pixels writeToFile:[destination stringByAppendingPathComponent:@"native-preview.png"] atomically:YES];
            NSDictionary *report = @{@"family": family(requested), @"exported": @(saved),
                @"bytes": attributes[NSFileSize] ?: @0, @"previewBytes": @(pixels.length),
                @"error": [failure description] ?: [copyError description] ?: @"",
                @"name": value(encoded, @"watchFaceName") ?: @""};
            NSData *json = [NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted error:nil];
            fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout); fflush(stdout);
            finished = YES;
        };
        ((void (*)(id, SEL, id, id))objc_msgSend)(NSClassFromString(@"NTKGreenfieldUtilities"),
            NSSelectorFromString(@"encodeRecipeFromDraftRecipe:completionBlock:"), recipe, completion);
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:45];
        while (!finished && deadline.timeIntervalSinceNow > 0) {
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.1]];
        }
        if (!finished) fprintf(stderr, "Native sharing encoder timed out\n");
        return saved ? 0 : 7;
    } @catch (NSException *exception) {
        fprintf(stderr, "%s: %s\n", exception.name.UTF8String, exception.reason.UTF8String);
        return 8;
    } }
}
