#import <UIKit/UIKit.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>
#import <CommonCrypto/CommonDigest.h>

// Simulator-only, transient UIKit cells. No Watch library, preferences or transport.
static id get(id object, NSString *name) {
    SEL selector = NSSelectorFromString(name);
    return [object respondsToSelector:selector] ? ((id (*)(id, SEL))objc_msgSend)(object, selector) : nil;
}
static void set(id object, NSString *name, id value) {
    ((void (*)(id, SEL, id))objc_msgSend)(object, NSSelectorFromString(name), value);
}
static NSArray *rgba(UIColor *color) {
    CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceExtendedSRGB);
    CGColorRef converted = CGColorCreateCopyByMatchingToColorSpace(space, kCGRenderingIntentDefault, color.CGColor, NULL);
    CGColorSpaceRelease(space);
    if (!converted || CGColorGetNumberOfComponents(converted) != 4) {
        if (converted) CGColorRelease(converted);
        return nil;
    }
    const CGFloat *c = CGColorGetComponents(converted);
    NSArray *result = @[@(c[0]), @(c[1]), @(c[2]), @(c[3])];
    CGColorRelease(converted);
    return result;
}
static int captureNativeChecks(int argc, const char *argv[]) { @autoreleasepool {
    if ((argc != 2 && argc != 4) ||
        (argc == 4 && strcmp(argv[2], "--colors") != 0) ||
        ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    NSString *directory = @(argv[1]);
    if ([NSFileManager.defaultManager fileExistsAtPath:directory] ||
        ![NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil]) return 2;
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKitCompanion.framework/NanoTimeKitCompanion", RTLD_NOW)) return 3;
    Class cellType = NSClassFromString(@"NTKPigmentCheckCell");
    // iOS 26.2 exports this implementation under its earlier class name.
    // Both implementations' complete _updateCheck functions are retained.
    if (!cellType) cellType = NSClassFromString(@"_NTKPigmentAddCell");
    id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
    Ivar checkIvar = class_getInstanceVariable(cellType, "_check");
    if (!cellType || !device || !checkIvar) {
        fprintf(stderr, "Native check unavailable: class=%s device=%s ivar=%s\n",
                cellType ? "present" : "missing", device ? "present" : "missing", checkIvar ? "present" : "missing");
        return 3;
    }
    NSMutableArray *rows = NSMutableArray.array;
    __block BOOL invalid = NO;
    @try {
        [[UITraitCollection traitCollectionWithUserInterfaceStyle:UIUserInterfaceStyleDark] performAsCurrentTraitCollection:^{
        NSMutableArray *colors = [@[NSNull.null, UIColor.whiteColor, UIColor.redColor, UIColor.blueColor,
                            [UIColor colorWithRed:.2 green:.6 blue:.4 alpha:1]] mutableCopy];
        if (argc == 4) {
            NSData *data = [NSData dataWithContentsOfFile:@(argv[3])];
            id extra = data.length <= 128 * 1024 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
            if (![extra isKindOfClass:NSArray.class] || [extra count] > 512) { invalid = YES; return; }
            CGColorSpaceRef space = CGColorSpaceCreateWithName(kCGColorSpaceExtendedSRGB);
            for (id value in extra) {
                if (![value isKindOfClass:NSArray.class] || [value count] != 4) { invalid = YES; break; }
                CGFloat components[4];
                for (NSUInteger i = 0; i < 4; i++) {
                    if (![value[i] isKindOfClass:NSNumber.class]) { invalid = YES; break; }
                    components[i] = [value[i] doubleValue];
                    if (!isfinite(components[i]) || components[i] < -2 || components[i] > 2) { invalid = YES; break; }
                }
                if (invalid || components[3] != 1) { invalid = YES; break; }
                CGColorRef cg = CGColorCreate(space, components);
                [colors addObject:[UIColor colorWithCGColor:cg]];
                CGColorRelease(cg);
            }
            CGColorSpaceRelease(space);
            if (invalid) return;
        }
        NSMutableSet *seen = NSMutableSet.set;
        for (id color in colors) { @autoreleasepool {
            id primary = color == NSNull.null ? NSNull.null : rgba(color);
            NSString *key = [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:primary
                                options:NSJSONWritingFragmentsAllowed error:nil] encoding:NSUTF8StringEncoding];
            if (!key) { invalid = YES; return; }
            if ([seen containsObject:key]) continue;
            [seen addObject:key];
            UICollectionViewCell *cell = [[cellType alloc] initWithFrame:CGRectMake(0, 0, 127.0 / 3, 127.0 / 3)];
            cell.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
            for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
                UIWindow *window = get(scene.delegate, @"window");
                if (window) { [window.rootViewController.view addSubview:cell]; break; }
            }
            set(cell, @"setDevice:", device);
            set(cell, @"setPrimaryColor:", color == NSNull.null ? nil : color);
            [cell setSelected:YES];
            [cell layoutIfNeeded];
            UIImageView *check = object_getIvar(cell, checkIvar);
            if (![check isKindOfClass:UIImageView.class] || !check.image || check.hidden) { invalid = YES; return; }
            CGSize size = check.bounds.size;
            if (!isfinite(size.width) || !isfinite(size.height) || size.width <= 0 || size.height <= 0 ||
                size.width > 96 || size.height > 96) { invalid = YES; return; }
            UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
            format.opaque = NO;
            format.preferredRange = UIGraphicsImageRendererFormatRangeStandard;
            UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:size format:format];
            UIImage *rendered = [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
                [check.layer renderInContext:context.CGContext];
            }];
            NSData *data = UIImagePNGRepresentation(rendered);
            if (!data || data.length > 256 * 1024) { invalid = YES; return; }
            unsigned char digest[CC_SHA256_DIGEST_LENGTH];
            CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
            NSMutableString *hash = NSMutableString.string;
            for (NSUInteger i = 0; i < sizeof(digest); i++) [hash appendFormat:@"%02x", digest[i]];
            NSString *leaf = [hash stringByAppendingString:@".png"];
            if (![data writeToFile:[directory stringByAppendingPathComponent:leaf] atomically:YES]) { invalid = YES; return; }
            [rows addObject:@{@"primary": primary,
                @"tint": rgba(check.tintColor), @"image": leaf,
                @"width": @(size.width), @"height": @(size.height), @"scale": @(rendered.scale),
                @"viewAppearance": @(check.traitCollection.userInterfaceStyle),
                @"imageRenderingMode": @(check.image.renderingMode), @"selectedVisible": @(!check.hidden)}];
            [cell setSelected:NO];
            if (!check.hidden) { invalid = YES; return; }
            [cell removeFromSuperview];
        }}
        }];
        if (invalid) return 4;
    } @catch (NSException *error) {
        fprintf(stderr, "%s: %s\n", error.name.UTF8String, error.reason.UTF8String); return 5;
    }
    NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"version":@1, @"appearance":@"dark", @"cellClass":NSStringFromClass(cellType), @"rows":rows}
                                                  options:NSJSONWritingSortedKeys error:nil];
    if (!json || json.length > 256 * 1024) return 4;
    if (![json writeToFile:[directory stringByAppendingPathComponent:@"capture.json"] atomically:YES]) return 4;
    // Simulator console relay can block a UIKit main queue on a large fwrite.
    // Keep the complete capture in a file and emit only a bounded receipt.
    if ([NSProcessInfo.processInfo.environment[@"NATIVE_CHECK_APPLICATION"] isEqual:@"1"]) {
        fprintf(stdout, "{\"rows\":%lu,\"capture\":\"capture.json\"}", (unsigned long)rows.count);
    } else {
        fwrite(json.bytes, 1, json.length, stdout);
    }
    return 0;
}}

static int probeArgc;
static const char **probeArgv;
@interface NativeCheckAppDelegate : UIResponder <UIApplicationDelegate>
@end
@implementation NativeCheckAppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    (void)application; (void)options;
    return YES;
}
@end
@interface NativeCheckSceneDelegate : UIResponder <UIWindowSceneDelegate>
@property(nonatomic, strong) UIWindow *window;
@end
@implementation NativeCheckSceneDelegate
- (void)scene:(UIScene *)scene willConnectToSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    (void)session; (void)options;
    if (![scene isKindOfClass:UIWindowScene.class]) exit(3);
    self.window = [[UIWindow alloc] initWithWindowScene:(UIWindowScene *)scene];
    self.window.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
    self.window.rootViewController = UIViewController.new;
    [self.window makeKeyAndVisible];
    dispatch_async(dispatch_get_main_queue(), ^{
        int result = captureNativeChecks(probeArgc, probeArgv);
        fflush(stdout); fflush(stderr); exit(result);
    });
}
@end
int main(int argc, char *argv[]) {
    @autoreleasepool {
        if ([NSProcessInfo.processInfo.environment[@"NATIVE_CHECK_APPLICATION"] isEqual:@"1"]) {
            probeArgc = argc; probeArgv = (const char **)argv;
            return UIApplicationMain(argc, argv, nil, NSStringFromClass(NativeCheckAppDelegate.class));
        }
        return captureNativeChecks(argc, (const char **)argv);
    }
}
