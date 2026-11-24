#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <dlfcn.h>

// Read-only research tool, compiled for watchOS Simulator. It never opens a transport,
// changes a face collection, or creates a production template from guessed fields.
// Selectors are from the watchOS 26.2 NanoTimeKit/ClockKit Objective-C metadata.
static id send0(id object, NSString *selector) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(selector));
}
static id send1(id object, NSString *selector, id argument) {
    return ((id (*)(id, SEL, id))objc_msgSend)(object, NSSelectorFromString(selector), argument);
}
static void apply(id face, id configuration) {
    ((void (*)(id, SEL, id))objc_msgSend)(face,
        NSSelectorFromString(@"applyConfiguration:"), configuration);
}

int main(void) {
    @autoreleasepool {
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) {
            fprintf(stderr, "NanoTimeKit load failed: %s\n", dlerror()); return 1;
        }
        @try {
            id device = send0(NSClassFromString(@"CLKDevice"), @"currentDevice");
            id manager = send0(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager");
            if (!device || !manager) { fprintf(stderr, "Native simulator device unavailable\n"); return 2; }
            NSMutableArray *rows = [NSMutableArray array];
            void (^observe)(id) = ^(id bundle) {
                @try {
                    id face = send1(bundle, @"defaultFaceForDevice:", device);
                    if (!face) return;
                    id slots = send0(face, @"_complicationSlotDescriptors");
                    NSMutableDictionary *families = [NSMutableDictionary dictionary];
                    if ([slots isKindOfClass:NSDictionary.class]) {
                        for (NSString *slot in slots) {
                            id descriptor = slots[slot];
                            id ranked = send0(descriptor, @"familiesRankedList");
                            if ([ranked isKindOfClass:NSArray.class]) families[slot] = ranked;
                            // Clear only the transient prototype: never ship simulator-specific intents.
                            ((void (*)(id, SEL, id, id))objc_msgSend)(face,
                                NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
                        }
                    }
                    // Some native defaults serialize a legacy token until they
                    // have passed through the bundle's configuration decoder.
                    // Normalize the transient prototype once, before comparing
                    // options, so unrelated modes cannot inherit that change.
                    id initialJSON = send0(face, @"JSONObjectRepresentation");
                    BOOL normalize = !initialJSON[@"customData"] &&
                        !initialJSON[@"resource directory"] && !send0(face, @"resourceDirectory");
                    id normalized = normalize ? ((id (*)(id, SEL, id, id, id))objc_msgSend)(
                        send0(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
                        NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"),
                        initialJSON, face, device) : nil;
                    if (normalized) apply(face, normalized);
                    id config = send0(face, @"JSONObjectRepresentation");
                    if (![config isKindOfClass:NSDictionary.class]) return;
                    NSMutableDictionary *options = [NSMutableDictionary dictionary];
                    NSMutableDictionary *fieldTitles = [NSMutableDictionary dictionary];
                    BOOL directoryPresent = send0(face, @"resourceDirectory") != nil;
                    id modes = send0(face, @"customEditModes");
                    for (NSNumber *modeNumber in [modes isKindOfClass:NSArray.class] ? modes : @[]) {
                        NSUInteger mode = modeNumber.unsignedIntegerValue;
                        id editSlots = ((id (*)(id, SEL, NSUInteger))objc_msgSend)(face,
                            NSSelectorFromString(@"slotsForCustomEditMode:"), mode);
                        if ([editSlots isKindOfClass:NSArray.class] && [editSlots count] > 0) continue;
                        id original = ((id (*)(id, SEL, NSUInteger, id))objc_msgSend)(face,
                            NSSelectorFromString(@"selectedOptionForCustomEditMode:slot:"), mode, nil);
                        NSUInteger count = ((NSUInteger (*)(id, SEL, NSUInteger, id))objc_msgSend)(face,
                            NSSelectorFromString(@"numberOfOptionsForCustomEditMode:slot:"), mode, nil);
                        if (count > 512) continue;
                        for (NSUInteger index = 0; index < count; index++) {
                            // Restore the complete baseline for every option:
                            // setters can affect several serialized fields.
                            if (normalized) apply(face, normalized);
                            id option = ((id (*)(id, SEL, NSUInteger, NSUInteger, id))objc_msgSend)(face,
                                NSSelectorFromString(@"optionAtIndex:forCustomEditMode:slot:"), index, mode, nil);
                            ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face,
                                NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), option, mode, nil);
                            NSDictionary *changed = send0(face, @"JSONObjectRepresentation");
                            for (NSString *field in changed[@"customization"]) {
                                id value = changed[@"customization"][field];
                                if (![value isKindOfClass:NSString.class] || [value isEqual:config[@"customization"][field]]) continue;
                                NSMutableArray *values = options[field];
                                if (!values) {
                                    values = [NSMutableArray array]; options[field] = values;
                                    id initial = config[@"customization"][field];
                                    if ([initial isKindOfClass:NSString.class]) [values addObject:@{
                                        @"value": initial, @"label": original ? (send0(original, @"localizedName") ?: initial) : initial,
                                    }];
                                }
                                NSString *label = send0(option, @"localizedName") ?: value;
                                NSDictionary *entry = @{@"value": value, @"label": label};
                                BOOL exists = NO;
                                for (NSDictionary *present in values)
                                    if ([present[@"value"] isEqual:value]) { exists = YES; break; }
                                if (!exists) [values addObject:entry];
                                fieldTitles[field] = ((id (*)(id, SEL, NSUInteger, id))objc_msgSend)([face class],
                                    NSSelectorFromString(@"localizedNameForCustomEditMode:forDevice:"), mode, device) ?: field;
                            }
                        }
                        if (normalized) apply(face, normalized);
                        else ((void (*)(id, SEL, id, NSUInteger, id))objc_msgSend)(face,
                            NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"), original, mode, nil);
                    }
                    BOOL resources = ((BOOL (*)(id, SEL))objc_msgSend)(face, NSSelectorFromString(@"shouldIncludeResourceDirectoryForSharing"));
                    NSMutableDictionary *row = [@{
                        @"bundle": send0([bundle class], @"identifier") ?: @"",
                        @"title": send1(bundle, @"galleryTitleForDevice:", device) ?: @"",
                        @"configuration": config,
                        @"requiresResources": @(resources),
                        @"slotFamilies": families,
                        @"resourceDirectoryPresent": @(directoryPresent),
                        @"options": options,
                        @"fieldTitles": fieldTitles,
                    } mutableCopy];
                    if (slots && [NSJSONSerialization isValidJSONObject:slots]) row[@"slotDescriptors"] = slots;
                    else if (slots) row[@"slotDescriptorDescription"] = [slots description];
                    [rows addObject:row];
                } @catch (NSException *exception) {
                    fprintf(stderr, "Bundle unavailable: %s\n", exception.reason.UTF8String);
                }
            };
            ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(manager,
                NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
            NSError *error = nil;
            NSData *json = [NSJSONSerialization dataWithJSONObject:rows options:NSJSONWritingPrettyPrinted error:&error];
            if (!json) { fprintf(stderr, "Native JSON unavailable: %s\n", error.localizedDescription.UTF8String); return 3; }
            fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout);
        } @catch (NSException *exception) {
            fprintf(stderr, "Native gallery unavailable: %s\n", exception.reason.UTF8String); return 4;
        }
    }
    return 0;
}
