#import <Foundation/Foundation.h>
#import <CoreFoundation/CoreFoundation.h>
#import <dlfcn.h>

// Read-only standalone simulator diagnostics. No snapshot/cache/pair mutation.
int main(void) {
    @autoreleasepool {
        if (!NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"]) return 1;
        void *library = dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW);
        if (!library) return 2;
        CFStringRef const *pointer = dlsym(library, "NTKFacePreferencesDomain");
        if (!pointer) return 3;
        NSString *domain = (__bridge NSString *)*pointer;
        if (![domain isKindOfClass:NSString.class]) return 3;
        NSMutableArray *rows = NSMutableArray.array;
        for (NSString *name in @[@"NTKDebugShowVisualIndicatorOnSnapshot", @"NTKDebugColorSynchronousRenders",
                                @"NTKDebugShowDebugClientSideAnimatedAnalogHands", @"NTKTritiumShowDebugViews",
                                @"_NTKFaceSnapshotCacheShowDebugAPLInSnapshot"]) {
            BOOL (*function)(void) = dlsym(library, name.UTF8String);
            if (!function) return 4;
            [rows addObject:@{@"function":name, @"enabled":@(function())}];
        }
        NSMutableDictionary *preferences = NSMutableDictionary.dictionary;
        for (NSString *key in @[@"ShowVisualIndicatorOnSnapshot", @"AnalogFaceShowDebug",
                                @"ShowDebugClientSideAnimatedAnalogHands", @"TritiumShowDebugViews"]) {
            id value = CFBridgingRelease(CFPreferencesCopyAppValue((__bridge CFStringRef)key, (__bridge CFStringRef)domain));
            preferences[key] = [value isKindOfClass:NSNumber.class] ? value : NSNull.null;
        }
        NSDictionary *result = @{@"schema":@1, @"domain":domain, @"nativeFlags":rows, @"preferences":preferences};
        NSData *data = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingSortedKeys error:nil];
        if (!data) return 5;
        fwrite(data.bytes, 1, data.length, stdout); fputc('\n', stdout);
    }
    return 0;
}
