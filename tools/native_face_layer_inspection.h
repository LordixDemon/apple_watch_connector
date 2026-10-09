#import <CommonCrypto/CommonDigest.h>

// Simulator research only: read the original, transient snapshot hierarchy.
// Read-only inspection uses no setters. The separate, opt-in component basis
// branch modifies only that transient prototype, never a library or transport.
// QuartzCore is present in the native Watch UI but its public declarations are
// unavailable to watchOS clients. Like the renderer's existing UIKit inspection,
// use runtime selectors with the documented getter ABI, without SDK overrides.
typedef struct { double m[16]; } NTKInspectionTransform;
static id ntkInspectGet(id object, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static CGFloat ntkInspectNumber(id object, NSString *name) {
    return ((CGFloat (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static BOOL ntkInspectFlag(id object, NSString *name) {
    return ((BOOL (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static CGColorRef ntkInspectCGColor(id object, NSString *name) {
    return ((CGColorRef (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static NSArray *ntkInspectRGBA(CGColorRef color) {
    if (!color) return nil;
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceExtendedSRGB);
    CGColorRef converted = CGColorCreateCopyByMatchingToColorSpace(space,
        kCGRenderingIntentDefault, color, NULL);
    CGColorSpaceRelease(space);
    if (!converted) return nil;
    NSArray *result = nil;
    if (CGColorGetNumberOfComponents(converted) == 4) {
        const CGFloat *values = CGColorGetComponents(converted);
        if (isfinite(values[0]) && isfinite(values[1]) &&
                isfinite(values[2]) && isfinite(values[3])) {
            result = @[@(values[0]), @(values[1]), @(values[2]), @(values[3])];
        }
    }
    CGColorRelease(converted);
    return result;
}

static NSArray *ntkInspectUIColor(id color) {
    SEL selector = NSSelectorFromString(@"CGColor");
    return [color respondsToSelector:selector]
        ? ntkInspectRGBA(((CGColorRef (*)(id, SEL))objc_msgSend)(color, selector)) : nil;
}

static NSArray *ntkInspectRect(CGRect rect) {
    return @[@(rect.origin.x), @(rect.origin.y), @(rect.size.width), @(rect.size.height)];
}

static NSString *ntkInspectImage(id contents, NSString *output, NSMutableSet *digests) {
    if (!contents) return nil;
    id image = [contents isKindOfClass:NSClassFromString(@"UIImage")] ? contents : nil;
    NSString *className = NSStringFromClass([contents class]);
    // Other layer contents (Metal surfaces, backing stores) are recorded by
    // class, not misrepresented as ordinary CGImage pixels.
    if (!image && ([className isEqual:@"CGImage"] ||
            ([className isEqual:@"__NSCFType"] &&
                CFGetTypeID((__bridge CFTypeRef)contents) == CGImageGetTypeID()))) {
        CGImageRef cg = (__bridge CGImageRef)contents;
        if (CGImageGetWidth(cg) > 1024 || CGImageGetHeight(cg) > 1024) return nil;
        image = ((id (*)(id, SEL, CGImageRef))objc_msgSend)(NSClassFromString(@"UIImage"),
            NSSelectorFromString(@"imageWithCGImage:"), cg);
    }
    NSData *(*png)(id) = dlsym(RTLD_DEFAULT, "UIImagePNGRepresentation");
    NSData *data = image && png ? png(image) : nil;
    if (!data.length || data.length > 4 * 1024 * 1024) return nil;
    unsigned char hash[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(data.bytes, (CC_LONG)data.length, hash);
    NSMutableString *digest = NSMutableString.string;
    for (NSUInteger i = 0; i < sizeof(hash); i++) [digest appendFormat:@"%02x", hash[i]];
    NSString *leaf = [digest stringByAppendingString:@".png"];
    if (![digests containsObject:digest]) {
        if (digests.count >= 128 || ![data writeToFile:
                [output stringByAppendingPathComponent:leaf] atomically:YES]) return nil;
        [digests addObject:digest];
    }
    return leaf;
}

static NSDictionary *ntkInspectLayer(id layer, NSString *path,
        NSString *output, NSMutableSet *digests, NSUInteger *count, NSUInteger depth) {
    if (!layer || *count >= 512 || depth > 32) return nil;
    (*count)++;
    CGRect bounds = ((CGRect (*)(id, SEL))objc_msgSend)(layer, NSSelectorFromString(@"bounds"));
    CGRect frame = ((CGRect (*)(id, SEL))objc_msgSend)(layer, NSSelectorFromString(@"frame"));
    CGPoint position = ((CGPoint (*)(id, SEL))objc_msgSend)(layer, NSSelectorFromString(@"position"));
    CGPoint anchor = ((CGPoint (*)(id, SEL))objc_msgSend)(layer, NSSelectorFromString(@"anchorPoint"));
    float opacity = ((float (*)(id, SEL))objc_msgSend)(layer, NSSelectorFromString(@"opacity"));
    NSMutableDictionary *row = [@{@"path": path, @"class": NSStringFromClass([layer class]),
        @"bounds": ntkInspectRect(bounds), @"frame": ntkInspectRect(frame),
        @"position": @[@(position.x), @(position.y)], @"anchorPoint": @[@(anchor.x), @(anchor.y)],
        @"opacity": @(opacity), @"hidden": @(ntkInspectFlag(layer, @"isHidden")),
        @"contentsScale": @(ntkInspectNumber(layer, @"contentsScale")),
        @"masksToBounds": @(ntkInspectFlag(layer, @"masksToBounds")),
        @"cornerRadius": @(ntkInspectNumber(layer, @"cornerRadius")),
        @"borderWidth": @(ntkInspectNumber(layer, @"borderWidth")),
        @"contentsGravity": ntkInspectGet(layer, @"contentsGravity") ?: @"",
        @"zPosition": @(ntkInspectNumber(layer, @"zPosition"))} mutableCopy];
    NTKInspectionTransform transform = ((NTKInspectionTransform (*)(id, SEL))objc_msgSend)(
        layer, NSSelectorFromString(@"transform"));
    NSMutableArray *matrix = NSMutableArray.array;
    for (NSUInteger i = 0; i < 16; i++) [matrix addObject:@(transform.m[i])];
    row[@"transform"] = matrix;
    NSArray *background = ntkInspectRGBA(ntkInspectCGColor(layer, @"backgroundColor"));
    NSArray *border = ntkInspectRGBA(ntkInspectCGColor(layer, @"borderColor"));
    if (background) row[@"backgroundColor"] = background;
    if (border) row[@"borderColor"] = border;
    if ([layer respondsToSelector:NSSelectorFromString(@"contentsMultiplyColor")]) {
        NSArray *multiply = ntkInspectRGBA(ntkInspectCGColor(layer, @"contentsMultiplyColor"));
        if (multiply) row[@"contentsMultiplyColor"] = multiply;
    }
    id contents = ntkInspectGet(layer, @"contents");
    if (contents) {
        row[@"contentsClass"] = NSStringFromClass([contents class]);
        NSString *image = ntkInspectImage(contents, output, digests);
        if (image) row[@"contentsImage"] = image;
    }
    id delegate = ntkInspectGet(layer, @"delegate");
    if (delegate) {
        row[@"delegateClass"] = NSStringFromClass([delegate class]);
        for (NSString *name in @[@"text", @"tintColor", @"textColor", @"backgroundColor"] ) {
            SEL selector = NSSelectorFromString(name);
            if (![delegate respondsToSelector:selector]) continue;
            id value = ((id (*)(id, SEL))objc_msgSend)(delegate, selector);
            if ([name isEqual:@"text"]) {
                if ([value isKindOfClass:NSString.class] && [value length] <= 256)
                    row[@"text"] = value;
            } else {
                NSArray *components = ntkInspectUIColor(value);
                if (components) row[[@"view." stringByAppendingString:name]] = components;
            }
        }
        if ([delegate respondsToSelector:NSSelectorFromString(@"faceColorPalette")]) {
            id palette = ((id (*)(id, SEL))objc_msgSend)(delegate,
                NSSelectorFromString(@"faceColorPalette"));
            NSMutableDictionary *roles = NSMutableDictionary.dictionary;
            for (NSString *name in @[@"primaryColor", @"secondaryColor", @"primaryShiftedColor",
                    @"secondaryShiftedColor", @"swatchPrimaryColor"]) {
                SEL selector = NSSelectorFromString(name);
                if (![palette respondsToSelector:selector]) continue;
                NSArray *components = ntkInspectUIColor(((id (*)(id, SEL))objc_msgSend)(palette, selector));
                if (components) roles[name] = components;
            }
            row[@"palette"] = roles;
        }
        if ([delegate respondsToSelector:NSSelectorFromString(@"attributedText")]) {
            id text = ntkInspectGet(delegate, @"attributedText");
            if ([text isKindOfClass:NSAttributedString.class] && [text length] <= 256) {
                NSMutableArray *runs = NSMutableArray.array;
                [text enumerateAttributesInRange:NSMakeRange(0, [text length]) options:0
                    usingBlock:^(NSDictionary *attributes, NSRange range, BOOL *stop) {
                        (void)stop;
                        NSMutableDictionary *colors = NSMutableDictionary.dictionary;
                        for (NSString *name in attributes) {
                            NSArray *rgba = ntkInspectUIColor(attributes[name]);
                            if (rgba) colors[name] = rgba;
                        }
                        [runs addObject:@{@"start": @(range.location), @"length": @(range.length),
                            @"colors": colors}];
                    }];
                row[@"attributedText"] = @{@"text": [text string], @"runs": runs};
            }
        }
    }
    if ([layer isKindOfClass:NSClassFromString(@"CAShapeLayer")]) {
        NSArray *fill = ntkInspectRGBA(ntkInspectCGColor(layer, @"fillColor"));
        NSArray *stroke = ntkInspectRGBA(ntkInspectCGColor(layer, @"strokeColor"));
        if (fill) row[@"fillColor"] = fill;
        if (stroke) row[@"strokeColor"] = stroke;
        row[@"lineWidth"] = @(ntkInspectNumber(layer, @"lineWidth"));
        row[@"lineCap"] = ntkInspectGet(layer, @"lineCap");
        row[@"lineJoin"] = ntkInspectGet(layer, @"lineJoin");
        CGPathRef path = ((CGPathRef (*)(id, SEL))objc_msgSend)(layer, NSSelectorFromString(@"path"));
        if (path) {
            NSMutableArray *elements = NSMutableArray.array;
            __block BOOL truncated = NO;
            CGPathApplyWithBlock(path, ^(const CGPathElement *element) {
                if (elements.count >= 4096) { truncated = YES; return; }
                NSUInteger points = element->type == kCGPathElementAddCurveToPoint ? 3
                    : element->type == kCGPathElementAddQuadCurveToPoint ? 2
                    : element->type == kCGPathElementCloseSubpath ? 0 : 1;
                NSMutableArray *values = [NSMutableArray arrayWithObject:@(element->type)];
                for (NSUInteger i = 0; i < points; i++) {
                    [values addObject:@(element->points[i].x)];
                    [values addObject:@(element->points[i].y)];
                }
                [elements addObject:values];
            });
            row[@"shapePath"] = elements;
            row[@"pathTruncated"] = @(truncated);
        }
    }
    NSMutableArray *children = NSMutableArray.array;
    for (id child in ntkInspectGet(layer, @"sublayers")) {
        NSDictionary *entry = ntkInspectLayer(child,
            [path stringByAppendingFormat:@"/%lu", children.count], output, digests, count, depth + 1);
        if (entry) [children addObject:entry];
    }
    row[@"children"] = children;
    return row;
}

static BOOL ntkInspectSnapshotLayers(id window, NSString *output, NSString *key) {
    if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length] || !window) return NO;
    id layer = ((id (*)(id, SEL))objc_msgSend)(window, NSSelectorFromString(@"layer"));
    NSUInteger count = 0;
    NSDictionary *tree = ntkInspectLayer(layer, @"0", output, NSMutableSet.set, &count, 0);
    NSData *data = tree ? [NSJSONSerialization dataWithJSONObject:
        @{@"version": @1, @"nodes": @(count), @"limitReached": @(count >= 512), @"layer": tree}
        options:0 error:nil] : nil;
    return data.length && data.length <= 2 * 1024 * 1024 && [data writeToFile:
        [output stringByAppendingPathComponent:[key stringByAppendingString:@"-layers.json"]] atomically:YES];
}

// Opt-in component capture mutates only the local prototype immediately before
// the original snapshotter draws it. Bindings come from multiple read-only
// captures, and each current value must match the prototype's native palette.
static id ntkLayerAtPath(id root, NSString *path) {
    if (![path isKindOfClass:NSString.class] || path.length > 256) return nil;
    NSArray *parts = [path componentsSeparatedByString:@"/"];
    if (parts.count > 33 || ![parts.firstObject isEqual:@"0"]) return nil;
    id layer = root;
    for (NSUInteger i = 1; i < parts.count; i++) {
        NSString *part = parts[i];
        NSUInteger index = part.integerValue;
        if (![[NSString stringWithFormat:@"%lu", index] isEqual:part]) return nil;
        NSArray *children = ntkInspectGet(layer, @"sublayers");
        if (index >= children.count) return nil;
        layer = children[index];
    }
    return layer;
}

static id ntkFindInspectionPalette(id layer, NSUInteger depth) {
    if (!layer || depth > 32) return nil;
    id delegate = ntkInspectGet(layer, @"delegate");
    if ([delegate respondsToSelector:NSSelectorFromString(@"faceColorPalette")]) {
        id palette = ntkInspectGet(delegate, @"faceColorPalette");
        if (palette) return palette;
    }
    for (id child in ntkInspectGet(layer, @"sublayers")) {
        id palette = ntkFindInspectionPalette(child, depth + 1);
        if (palette) return palette;
    }
    return nil;
}

static BOOL ntkSameInspectionColor(NSArray *a, NSArray *b) {
    if (a.count != 4 || b.count != 4) return NO;
    for (NSUInteger i = 0; i < 4; i++)
        if (fabs([a[i] doubleValue] - [b[i] doubleValue]) > 0.00001) return NO;
    return YES;
}

static BOOL ntkApplyComponentBasis(id window, NSArray *bindings, NSString *whiteRole) {
    if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length] ||
            ![bindings isKindOfClass:NSArray.class] || bindings.count < 1 || bindings.count > 128 ||
            ![whiteRole isKindOfClass:NSString.class]) return NO;
    NSSet *roles = [NSSet setWithArray:@[@"primaryColor", @"secondaryColor",
        @"primaryShiftedColor", @"secondaryShiftedColor"]];
    NSMutableSet *groups = NSMutableSet.set;
    for (id binding in bindings) {
        if (![binding isKindOfClass:NSDictionary.class]) return NO;
        id group = binding[@"group"] ?: binding[@"role"];
        if (![group isKindOfClass:NSString.class] || ![group length] || [group length] > 128) return NO;
        [groups addObject:group];
    }
    if (![whiteRole isEqual:@"black"] && ![groups containsObject:whiteRole]) return NO;
    id root = ntkInspectGet(window, @"layer");
    id palette = ntkFindInspectionPalette(root, 0);
    if (!palette) return NO;
    NSMutableArray *validated = NSMutableArray.array;
    for (NSDictionary *binding in bindings) {
        if (![binding isKindOfClass:NSDictionary.class] || ![roles containsObject:binding[@"role"]]) return NO;
        if (![binding[@"class"] isKindOfClass:NSString.class] ||
                ![binding[@"property"] isKindOfClass:NSString.class]) return NO;
        id layer = ntkLayerAtPath(root, binding[@"path"]);
        if (!layer || ![NSStringFromClass([layer class]) isEqual:binding[@"class"]]) return NO;
        NSString *role = binding[@"role"], *property = binding[@"property"];
        if (![palette respondsToSelector:NSSelectorFromString(role)]) return NO;
        NSArray *native = ntkInspectUIColor(ntkInspectGet(palette, role));
        id delegate = ntkInspectGet(layer, @"delegate");
        NSArray *current = nil;
        if ([property isEqual:@"backgroundColor"] || [property isEqual:@"contentsMultiplyColor"]) {
            if (![layer respondsToSelector:NSSelectorFromString(property)]) return NO;
            NSString *setter = [property isEqual:@"backgroundColor"] ? @"setBackgroundColor:" : @"setContentsMultiplyColor:";
            if (![layer respondsToSelector:NSSelectorFromString(setter)]) return NO;
            current = ntkInspectRGBA(ntkInspectCGColor(layer, property));
        } else if ([property isEqual:@"attributedText"]) {
            if (![delegate respondsToSelector:NSSelectorFromString(@"attributedText")] ||
                    ![delegate respondsToSelector:NSSelectorFromString(@"setAttributedText:")]) return NO;
            NSAttributedString *text = ntkInspectGet(delegate, @"attributedText");
            NSArray *range = binding[@"range"];
            NSString *attribute = binding[@"attribute"];
            if (![text isKindOfClass:NSAttributedString.class] ||
                    ![text.string isEqual:binding[@"text"]] || ![range isKindOfClass:NSArray.class] ||
                    range.count != 2 || ![range[0] isKindOfClass:NSNumber.class] ||
                    ![range[1] isKindOfClass:NSNumber.class] || ![attribute isEqual:@"NSColor"]) return NO;
            NSUInteger start = [range[0] unsignedIntegerValue], length = [range[1] unsignedIntegerValue];
            if (!length || start >= text.length || length > text.length - start) return NO;
            for (NSUInteger offset = start; offset < start + length; offset++) {
                NSArray *value = ntkInspectUIColor([text attribute:attribute atIndex:offset effectiveRange:NULL]);
                if (!ntkSameInspectionColor(value, native)) return NO;
                current = value;
            }
        } else return NO;
        if (!ntkSameInspectionColor(current, native)) return NO;
        [validated addObject:@{@"binding": binding, @"layer": layer}];
    }
    // Only after all targets are verified may the prototype change.
    id black = ntkInspectGet(NSClassFromString(@"UIColor"), @"blackColor");
    id white = ntkInspectGet(NSClassFromString(@"UIColor"), @"whiteColor");
    id transaction = NSClassFromString(@"CATransaction");
    ((void (*)(id, SEL))objc_msgSend)(transaction, NSSelectorFromString(@"begin"));
    ((void (*)(id, SEL, BOOL))objc_msgSend)(transaction, NSSelectorFromString(@"setDisableActions:"), YES);
    for (NSDictionary *entry in validated) {
        NSDictionary *binding = entry[@"binding"];
        id layer = entry[@"layer"], color = [(binding[@"group"] ?: binding[@"role"]) isEqual:whiteRole] ? white : black;
        NSString *property = binding[@"property"];
        if ([property isEqual:@"attributedText"]) {
            id delegate = ntkInspectGet(layer, @"delegate");
            NSMutableAttributedString *text = [ntkInspectGet(delegate, property) mutableCopy];
            NSArray *range = binding[@"range"];
            [text addAttribute:binding[@"attribute"] value:color range:
                NSMakeRange([range[0] unsignedIntegerValue], [range[1] unsignedIntegerValue])];
            ((void (*)(id, SEL, id))objc_msgSend)(delegate, NSSelectorFromString(@"setAttributedText:"), text);
        } else {
            NSString *setter = [property isEqual:@"backgroundColor"] ? @"setBackgroundColor:" : @"setContentsMultiplyColor:";
            ((void (*)(id, SEL, CGColorRef))objc_msgSend)(layer, NSSelectorFromString(setter),
                ((CGColorRef (*)(id, SEL))objc_msgSend)(color, NSSelectorFromString(@"CGColor")));
        }
    }
    ((void (*)(id, SEL))objc_msgSend)(transaction, NSSelectorFromString(@"commit"));
    ((void (*)(id, SEL))objc_msgSend)(transaction, NSSelectorFromString(@"flush"));
    return YES;
}
