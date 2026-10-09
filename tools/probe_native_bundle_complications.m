#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Research only. No transport, installed-provider enumeration, or face mutation.
// Deliberately fictional identifiers distinguish codec evidence from availability.
static id msg(id object, NSString *selector) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(selector));
}
int main(void) {
    @autoreleasepool {
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 2;
        Class descriptorClass = NSClassFromString(@"CLKComplicationDescriptor");
        Class bundleClass = NSClassFromString(@"NTKBundleComplication");
        Class baseClass = NSClassFromString(@"NTKComplication");
        NSMutableArray *rows = [NSMutableArray array];
        NSArray *cases = @[@{}, @{@"choice":@"sample", @"count":@7},
            @{@"nested":@{@"values":@[@"one", @7, @YES]}},
            @{@"nullable":[NSNull null], @"values":@[[NSNull null], @3]}];
        for (NSDictionary *info in cases) {
            @try {
                id descriptor = ((id (*)(id, SEL, id, id, id, id))objc_msgSend)(
                    msg(descriptorClass, @"alloc"),
                    NSSelectorFromString(@"initWithIdentifier:displayName:supportedFamilies:userInfo:"),
                    @"research-only-descriptor", @"Research only", @[@8, @9], info);
                if (info[@"nested"]) {
                    NSUserActivity *activity = [[NSUserActivity alloc] initWithActivityType:@"research.only.activity"];
                    activity.title = @"Research action";
                    activity.userInfo = @{@"entity":@"research-only", @"number":@7};
                    id wrapped = ((id (*)(id, SEL, id))objc_msgSend)(
                        msg(NSClassFromString(@"CLKUserActivity"), @"alloc"),
                        NSSelectorFromString(@"initWithUserActivity:"), activity);
                    NSString *encoded = msg(wrapped, @"encodedUserActivity");
                    if (!encoded.length) {
                        fprintf(stderr, "Native user activity encoding failed\n"); return 7;
                    }
                    ((void (*)(id, SEL, id))objc_msgSend)(descriptor,
                        NSSelectorFromString(@"setClkUserActivity:"), wrapped);
                    ((void (*)(id, SEL, BOOL))objc_msgSend)(descriptor,
                        NSSelectorFromString(@"setNeedsAppNotify:"), YES);
                }
                id value = ((id (*)(id, SEL, id, id, id))objc_msgSend)(NSClassFromString(@"CLKCBundleComplication"),
                    NSSelectorFromString(@"complicationWithBundleIdentifier:appBundleIdentifier:complicationDescriptor:"),
                    @"research.only.extension", @"research.only.app", descriptor);
                id complication = ((id (*)(id, SEL, id))objc_msgSend)(bundleClass,
                    NSSelectorFromString(@"bundledComplicationWithComplication:"), value);
                if (!complication) {
                    fprintf(stderr, "Native codec refused research value\n");
                    return 6;
                }
                NSDictionary *json = msg(complication, @"JSONObjectRepresentation");
                id roundtrip = ((id (*)(id, SEL, id))objc_msgSend)(baseClass,
                    NSSelectorFromString(@"complicationWithJSONObjectRepresentation:"), json);
                // A codec roundtrip is not proof of an installed provider.
                NSDictionary *again = roundtrip ? msg(roundtrip, @"JSONObjectRepresentation") : nil;
                NSError *error = nil;
                NSData *archive = [NSKeyedArchiver archivedDataWithRootObject:@[descriptor]
                                                      requiringSecureCoding:YES error:&error];
                if (!archive || ![NSJSONSerialization isValidJSONObject:json]) return 3;
                [rows addObject:@{@"configuration":json,
                    @"descriptor":msg(descriptor, @"JSONObjectRepresentation"),
                    @"parserAccepted":@(roundtrip != nil),
                    @"roundtripEqual":@([json isEqual:again]),
                    @"descriptorArchiveBase64":[archive base64EncodedStringWithOptions:0]}];
            } @catch (NSException *exception) {
                fprintf(stderr, "%s\n", exception.reason.UTF8String); return 4;
            }
        }
        NSData *data = [NSJSONSerialization dataWithJSONObject:rows options:NSJSONWritingPrettyPrinted error:nil];
        if (!data) return 5;
        fwrite(data.bytes, 1, data.length, stdout); fputc('\n', stdout);
    }
    return 0;
}
