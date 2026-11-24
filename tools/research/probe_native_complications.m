#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Research only: read native built-in JSON and family compatibility on Simulator.
// Never opens a Watch transport or changes any face collection.
static id msg(id object, NSString *selector) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(selector));
}
int main(void) {
    @autoreleasepool {
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 2;
        id device = msg(NSClassFromString(@"CLKDevice"), @"currentDevice");
        Class base = NSClassFromString(@"NTKComplication");
        Class source = NSClassFromString(@"NTKComplicationDataSource");
        NSMutableArray *rows = [NSMutableArray array];
        for (NSUInteger type = 0; type <= 64; type++) {
            @try {
                NSArray *choices = ((id (*)(id, SEL, NSUInteger))objc_msgSend)(base,
                    NSSelectorFromString(@"allComplicationsOfType:"), type);
                for (id complication in choices) {
                    NSDictionary *json = msg(complication, @"JSONObjectRepresentation");
                    if (![NSJSONSerialization isValidJSONObject:json]) continue;
                    NSMutableArray *families = [NSMutableArray array];
                    for (NSUInteger family = 0; family <= 12; family++) {
                        BOOL allowed = ((BOOL (*)(id, SEL, NSUInteger, NSUInteger, id))objc_msgSend)(source,
                            NSSelectorFromString(@"acceptsComplicationType:withFamily:forDevice:"), type, family, device);
                        if (allowed) [families addObject:@(family)];
                    }
                    id roundtrip = ((id (*)(id, SEL, id))objc_msgSend)(base,
                        NSSelectorFromString(@"complicationWithJSONObjectRepresentation:"), json);
                    NSDictionary *again = roundtrip ? msg(roundtrip, @"JSONObjectRepresentation") : nil;
                    [rows addObject:@{@"type":@(type), @"class":NSStringFromClass([complication class]),
                        @"app":msg(complication, @"appIdentifier") ?: @"",
                        @"name":msg(complication, @"localizedKeylineLabelText") ?: @"",
                        @"families":families, @"configuration":json,
                        @"roundtripEqual":@([json isEqual:again])}];
                }
            } @catch (NSException *exception) {
                fprintf(stderr, "type%lu unavailable:%s\n", type, exception.reason.UTF8String);
            }
        }
        NSError *error = nil;
        NSData *data = [NSJSONSerialization dataWithJSONObject:rows options:NSJSONWritingPrettyPrinted error:&error];
        if (!data) { fprintf(stderr, "%s\n", error.description.UTF8String); return 3; }
        fwrite(data.bytes, 1, data.length, stdout); fputc('\n', stdout);
    }
    return 0;
}
