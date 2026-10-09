#import <Foundation/Foundation.h>
#import <dlfcn.h>
#import <math.h>
#import <stdlib.h>

// Simulator-only serialization probe. It never executes/donates an intent,
// creates a connection, or sends a command to a physical Watch.
// INPUT PROPERTY [SECONDS NEW_OUTPUT] -- supply only controlled research data.
int main(int argc, const char **argv) {
    @autoreleasepool {
        if ((argc != 3 && argc != 5) || !getenv("SIMULATOR_ROOT")) return 2;
        if (!dlopen("/System/Library/Frameworks/Intents.framework/Intents", RTLD_NOW)) return 3;
        NSData *data = [NSData dataWithContentsOfFile:@(argv[1])];
        if (!data.length || data.length > 98304) return 4;
        Class intentClass = NSClassFromString(@"INIntent");
        if (!intentClass) return 3;
        NSError *error = nil;
        id intent = [NSKeyedUnarchiver unarchivedObjectOfClass:intentClass fromData:data error:&error];
        if (!intent || error) {
            fprintf(stderr, "Native intent decoding failed\n");
            return 5;
        }
        @try {
            id before = [intent valueForKey:@(argv[2])];
            if (![before isKindOfClass:NSNumber.class]) return 6;
            if (argc == 5) {
                char *end = NULL;
                double seconds = strtod(argv[3], &end);
                if (end == argv[3] || *end || !isfinite(seconds) || seconds < 0) return 6;
                NSString *output = @(argv[4]);
                if ([NSFileManager.defaultManager fileExistsAtPath:output]) return 8;
                [intent setValue:@(seconds) forKey:@(argv[2])];
                NSData *encoded = [NSKeyedArchiver archivedDataWithRootObject:intent
                    requiringSecureCoding:YES error:&error];
                if (!encoded || error || ![encoded writeToFile:output atomically:YES]) return 8;
            }
            id value = [intent valueForKey:@(argv[2])];
            if (![value isKindOfClass:NSNumber.class]) return 6;
            // Only the explicitly requested research scalar; no private routing.
            printf("Native numeric parameter=%.6f\n", [value doubleValue]);
        } @catch (NSException *exception) {
            fprintf(stderr, "Native parameter lookup failed\n");
            return 7;
        }
    }
    return 0;
}
