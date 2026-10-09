#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>

static IMP originalMediaValidator;
static BOOL observeMedia(id self, SEL selector, id name, id type, unsigned long long minimum, unsigned long long maximum) {
    fprintf(stderr, "native media size bounds: %llu..%llu\n", minimum, maximum);
    return ((BOOL (*)(id, SEL, id, id, unsigned long long, unsigned long long))originalMediaValidator)(self, selector, name, type, minimum, maximum);
}

// Research only. Calls the native type-2 unzip helper without a Watch transport.
int main(int argc, char **argv) {
    @autoreleasepool { @try {
        if (argc != 2) return 2;
        void *framework = dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW);
        id (*unzip)(id) = dlsym(framework, "NTKDFaceResourceDirectoryFromPayloadPath");
        if (!unzip) { fprintf(stderr, "Native helper unavailable\n"); return 3; }
        NSString *directory = unzip(@(argv[1]));
        if (!directory) { fprintf(stderr, "Native unzip returned no directory\n"); return 4; }
        NSArray *files = [[NSFileManager defaultManager] subpathsAtPath:directory];
        dlopen("/System/Library/NanoTimeKit/FaceBundles/NTKParmesanFaceBundle.bundle/NTKParmesanFaceBundle", RTLD_NOW);
        id device = ((id (*)(id, SEL))objc_msgSend)(NSClassFromString(@"CLKDevice"), NSSelectorFromString(@"currentDevice"));
        id bundle = ((id (*)(id, SEL))objc_msgSend)(NSClassFromString(@"NTKParmesanFaceBundle"), NSSelectorFromString(@"new"));
        id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle, NSSelectorFromString(@"defaultFaceForDevice:"), device);
        id manifest = ((id (*)(id, SEL, id))objc_msgSend)(NSClassFromString(@"NTKParmesanResourcesManifest"),
            NSSelectorFromString(@"manifestForResourceDirectory:"), directory);
        Method validator = class_getInstanceMethod(NSClassFromString(@"NTKBasePhotoResourcesManifest"),
            NSSelectorFromString(@"resourceWithName:isValidMediaAssetOfType:withMinFileSize:maxFileSize:"));
        const char *encoding = validator ? method_getTypeEncoding(validator) : "";
        fprintf(stderr, "native media signature: %s\n", encoding);
        if (validator && strstr(encoding, "Q") && !strstr(encoding, "d"))
            originalMediaValidator = method_setImplementation(validator, (IMP)observeMedia);
        NSError *error = nil;
        BOOL valid = ((BOOL (*)(id, SEL, NSError **))objc_msgSend)(manifest,
            NSSelectorFromString(@"validateManifestWithError:"), &error);
        NSDictionary *result = @{@"files":files ?: @[], @"valid":@(valid), @"error":error.description ?: @"", @"faceLoaded":@(face != nil)};
        NSData *json = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:&error];
        if (json) { fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout); }
        [[NSFileManager defaultManager] removeItemAtPath:directory error:nil];
        return valid && json ? 0 : 5;
    } @catch (NSException *exception) { fprintf(stderr, "%s\n", exception.description.UTF8String); return 6; } }
}
