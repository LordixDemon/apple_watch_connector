#import <Foundation/Foundation.h>
#import <objc/runtime.h>
#import <dlfcn.h>

// Read-only simulator inspection: enumerate implementations, never invoke them.
int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc != 3 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        NSString *path = @(argv[1]);
        if (![path hasPrefix:@"/System/Library/NanoTimeKit/FaceBundles/"] ||
                !dlopen(path.UTF8String, RTLD_NOW)) return 3;
        Class target = NSClassFromString(@(argv[2]));
        if (!target) return 4;
        NSMutableArray *rows = NSMutableArray.array;
        for (NSUInteger kind = 0; kind < 2; kind++) {
            unsigned int count = 0;
            Method *methods = class_copyMethodList(kind ? object_getClass(target) : target, &count);
            for (unsigned int i = 0; i < count; i++) {
                IMP implementation = method_getImplementation(methods[i]);
                Dl_info info;
                if (dladdr((const void *)implementation, &info) && info.dli_fbase) {
                    [rows addObject:@{@"selector": NSStringFromSelector(method_getName(methods[i])),
                        @"classMethod": @(kind != 0),
                        @"offset": @((uintptr_t)implementation - (uintptr_t)info.dli_fbase),
                        @"image": @(info.dli_fname)}];
                }
            }
            free(methods);
        }
        NSData *data = [NSJSONSerialization dataWithJSONObject:rows options:0 error:nil];
        if (!data) return 5;
        fwrite(data.bytes, 1, data.length, stdout);
        fputc('\n', stdout);
        return 0;
    }
}
