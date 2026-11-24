#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Local simulator factory probe. No paired collection manager or transport.
static id get(id object, NSString *name) {
    SEL selector = NSSelectorFromString(name);
    return [object respondsToSelector:selector] ? ((id (*)(id, SEL))objc_msgSend)(object, selector) : nil;
}
static NSString *string(id object, NSString *name) {
    id value = get(object, name);
    return [value isKindOfClass:NSString.class] ? value : @"";
}
int main(void) {
    @autoreleasepool {
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 1;
        id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
        Class type = NSClassFromString(@"NTKGalleryCollection");
        SEL factory = NSSelectorFromString(@"galleryCollectionsForDevice:");
        if (!device || ![type respondsToSelector:factory]) {
            fprintf(stderr, "Native standalone gallery factory unavailable\n"); return 2;
        }
        @try {
            id collections = ((id (*)(id, SEL, id))objc_msgSend)(type, factory, device);
            if (![collections isKindOfClass:NSArray.class] || [collections count] > 256) return 3;
            for (id collection in collections) {
                SEL load = NSSelectorFromString(@"loadFaces");
                if ([collection respondsToSelector:load]) ((void (*)(id, SEL))objc_msgSend)(collection, load);
            }
            NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5];
            while (deadline.timeIntervalSinceNow > 0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.05]];
            NSMutableArray *rows = NSMutableArray.array;
            for (id collection in collections) {
                NSMutableArray *recipes = NSMutableArray.array;
                SEL countSelector = NSSelectorFromString(@"numberOfFaces");
                SEL faceSelector = NSSelectorFromString(@"faceAtIndex:");
                if (![collection respondsToSelector:countSelector] || ![collection respondsToSelector:faceSelector]) return 5;
                NSUInteger count = ((NSUInteger (*)(id, SEL))objc_msgSend)(collection, countSelector);
                if (count > 1024) return 6;
                for (NSUInteger index = 0; index < count; index++) {
                    id face = ((id (*)(id, SEL, NSUInteger))objc_msgSend)(collection, faceSelector, index);
                    id recipe = get(face, @"JSONObjectRepresentation");
                    if ([recipe isKindOfClass:NSDictionary.class]) [recipes addObject:recipe];
                }
                [rows addObject:@{@"class":NSStringFromClass([collection class]),
                    @"title":string(collection, @"title"),
                    @"callout":string(collection, @"calloutName"), @"faces":recipes}];
            }
            NSDictionary *result = @{@"schema":@1, @"runtime":@"watchOS26.2/23S303", @"collections":rows};
            NSData *json = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingSortedKeys error:nil];
            fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout);
        } @catch (NSException *error) {
            fprintf(stderr, "%s: %s\n", error.name.UTF8String, error.reason.UTF8String); return 4;
        }
    }
    return 0;
}
