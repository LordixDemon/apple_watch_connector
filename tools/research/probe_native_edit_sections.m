#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <objc/message.h>
#import <dlfcn.h>

static id companionStyle(id object) {
    SEL selector = NSSelectorFromString(@"swatchStyle");
    return [object respondsToSelector:selector]
        ? @(((NSInteger (*)(id, SEL))objc_msgSend)(object, selector)) : NSNull.null;
}

// Simulator-only read-only model extraction. No paired-device/library APIs.
static id get(id object, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static NSUInteger number(id object, NSString *name) {
    return ((NSUInteger (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static id geometry(id collection, id face, id view) {
    // Exercise the original factory rather than assuming a cell from its type.
    // All objects are transient simulator prototypes with no table controller.
    id controller = ((id (*)(id, SEL, id, id, BOOL, id, id))objc_msgSend)(
        get(NSClassFromString(@"NTKCFaceDetailEditOptionSectionController"), @"alloc"),
        NSSelectorFromString(@"initWithTableViewController:face:inGallery:editOptionCollection:faceView:"),
        nil, face, YES, collection, view);
    if (![controller respondsToSelector:NSSelectorFromString(@"cell")]) return NSNull.null;
    id cell = get(controller, @"cell");
    if (!cell || ![cell respondsToSelector:NSSelectorFromString(@"rowHeight")]) return NSNull.null;
    NSMutableDictionary *result = [@{@"cellClass": NSStringFromClass([cell class]),
        @"controllerClass": NSStringFromClass([controller class]),
        @"rowHeight": @(((CGFloat (*)(id, SEL))objc_msgSend)(cell, NSSelectorFromString(@"rowHeight")))} mutableCopy];
    if ([cell respondsToSelector:NSSelectorFromString(@"swatchFrame")]) {
        CGRect frame = ((CGRect (*)(id, SEL))objc_msgSend)(cell, NSSelectorFromString(@"swatchFrame"));
        result[@"swatchFrame"] = @[@(frame.origin.x), @(frame.origin.y), @(frame.size.width), @(frame.size.height)];
        id layout = get(cell, @"layout");
        CGSize size = ((CGSize (*)(id, SEL))objc_msgSend)(layout, NSSelectorFromString(@"itemSize"));
        result[@"itemSize"] = @[@(size.width), @(size.height)];
        result[@"lineSpacing"] = @(((CGFloat (*)(id, SEL))objc_msgSend)(layout, NSSelectorFromString(@"minimumLineSpacing")));
    }
    return result;
}
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        BOOL companion = argc == 2 && strcmp(argv[1], "--companion") == 0;
        if ((argc != 1 && !companion) || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
        [NSUserDefaults.standardUserDefaults setVolatileDomain:@{
            @"AppleLanguages": @[@"en-US"], @"AppleLocale": @"en_US"} forName:NSArgumentDomain];
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        // Companion categories exist on iOS. Watch capture remains compatible.
        dlopen("/System/Library/PrivateFrameworks/NanoTimeKitCompanion.framework/NanoTimeKitCompanion", RTLD_NOW);
        id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
        NSMutableArray *rows = NSMutableArray.array;
        void (^observe)(id) = ^(id bundle) { @try {
            id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle,
                NSSelectorFromString(@"defaultFaceForDevice:"), device);
            NSDictionary *initial = face ? get(face, @"JSONObjectRepresentation") : nil;
            if (!initial || initial[@"customData"] || initial[@"resource directory"] || get(face, @"resourceDirectory")) return;
            for (NSString *slot in get(face, @"_complicationSlotDescriptors"))
                ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
            initial = get(face, @"JSONObjectRepresentation");
            id configuration = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
                get(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
                NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), initial, face, device);
            ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), configuration);
            NSDictionary *baseline = get(face, @"JSONObjectRepresentation");
            NSString *family = baseline[@"bundle id"] ? [@"bundle:" stringByAppendingString:baseline[@"bundle id"]]
                : baseline[@"face type"] ? [@"type:" stringByAppendingString:baseline[@"face type"]] : nil;
            if (!family || ![face respondsToSelector:NSSelectorFromString(@"editOptionsForCustomEditModes")]) return;
            NSArray *collections = get(face, @"editOptionsForCustomEditModes");
            if (![collections isKindOfClass:NSArray.class] || collections.count > 64) return;
            NSMutableArray *sections = NSMutableArray.array;
            for (id collection in collections) {
                NSUInteger mode = number(collection, @"mode");
                id slot = get(collection, @"slot");
                NSArray *options = get(collection, @"options");
                if (![options isKindOfClass:NSArray.class] || options.count > 512) return;
                NSMutableArray *values = NSMutableArray.array;
                for (id option in options) {
                    ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), configuration);
                    ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face,
                        NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), option, mode, slot);
                    [values addObject:@{@"customization": get(face, @"JSONObjectRepresentation")[@"customization"] ?: @{},
                        @"swatchStyle": companionStyle(option), @"optionClass": NSStringFromClass([option class]),
                        @"label": get(option, @"localizedName") ?: @""}];
                }
                ((void (*)(id, SEL, id))objc_msgSend)(face, NSSelectorFromString(@"applyConfiguration:"), configuration);
                [sections addObject:@{@"mode": @(mode), @"slot": slot ?: NSNull.null,
                    @"collectionType": @(number(collection, @"collectionType")),
                    @"swatchStyle": companionStyle(collection),
                    @"title": get(collection, @"localizedName") ?: @"", @"options": values}];
            }
            if (companion) {
                // Construct the view after enumeration: notifying live native
                // renderers about every prototype option is unnecessary and
                // some bundles require resources absent from this probe.
                __attribute__((objc_precise_lifetime)) id controller = ((id (*)(id, SEL, id, id))objc_msgSend)(
                    get(NSClassFromString(@"NTKFaceViewController"), @"alloc"),
                    NSSelectorFromString(@"initWithFace:configuration:"), face, nil);
                id view = get(controller, @"faceView");
                for (NSUInteger index = 0; index < collections.count; index++) {
                    id collection = collections[index];
                    NSMutableDictionary *section = [sections[index] mutableCopy];
                    id measured = NSNull.null;
                    if (!get(collection, @"slot") && [@[@0, @2, @3] containsObject:@(number(collection, @"collectionType"))]) {
                        @try { measured = geometry(collection, face, view); }
                        @catch (NSException *error) {
                            fprintf(stderr, "Native geometry unavailable: %s\n", error.reason.UTF8String);
                        }
                    }
                    section[@"geometry"] = measured;
                    sections[index] = section;
                }
            }
            if (rows.count >= 256) return;
            [rows addObject:@{@"family": family, @"baseline": baseline[@"customization"] ?: @{}, @"sections": sections}];
        } @catch (NSException *error) {
            fprintf(stderr, "Native sections unavailable: %s\n", error.reason.UTF8String);
        }};
        ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
            NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
        NSData *data = [NSJSONSerialization dataWithJSONObject:rows options:0 error:nil];
        if (!data || data.length > 16 * 1024 * 1024 || !rows.count) return 4;
        fwrite(data.bytes, 1, data.length, stdout);
        return 0;
    }
}
