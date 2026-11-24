#import <UIKit/UIKit.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"
#import "native_face_layer_inspection.h"
#pragma clang diagnostic pop

// Simulator scene only. The original factory supplies the table/collection
// cells and their options; the prototype never enters a face library.
static int probeArgc;
static char **probeArgv;
static id get(id object, NSString *name) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static NSUInteger number(id object, NSString *name) {
    return ((NSUInteger (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(name));
}
static void layoutTree(UIView *view) {
    [view setNeedsLayout]; [view layoutIfNeeded];
    for (UIView *child in view.subviews) layoutTree(child);
}
static void activateOptions(UIView *view, NSUInteger *index) {
    if ([NSStringFromClass(view.class) isEqual:@"_NTKCFaceDetailCollectionCell"]) {
        SEL selector = NSSelectorFromString(@"setActive:animated:forced:");
        Method method = class_getInstanceMethod(view.class, selector);
        NSMethodSignature *signature = method ? [NSMethodSignature signatureWithObjCTypes:method_getTypeEncoding(method)] : nil;
        if (!signature || signature.numberOfArguments != 5 || strcmp(signature.methodReturnType, @encode(void))) abort();
        for (NSUInteger argument = 2; argument < 5; argument++)
            if (strcmp([signature getArgumentTypeAtIndex:argument], @encode(BOOL))) abort();
        ((void (*)(id, SEL, BOOL, BOOL, BOOL))objc_msgSend)(view, selector, (*index)++ == 0, NO, YES);
    }
    for (UIView *child in view.subviews) activateOptions(child, index);
}
static id views(UIView *view, UIView *root, NSUInteger depth) {
    if (depth > 12 || view.subviews.count > 128) return NSNull.null;
    NSMutableDictionary *row = [@{@"class":NSStringFromClass(view.class),
        @"frame":ntkInspectRect([view convertRect:view.bounds toView:root]),
        @"hidden":@(view.hidden), @"alpha":@(view.alpha)} mutableCopy];
    if ([view isKindOfClass:UILabel.class]) {
        UILabel *label = (UILabel *)view;
        row[@"text"] = label.text ?: @"";
        row[@"fontSize"] = @(label.font.pointSize);
        row[@"lineHeight"] = @(label.font.lineHeight);
        row[@"ascender"] = @(label.font.ascender);
        row[@"fontDescriptor"] = label.font.fontDescriptor.fontAttributes;
        row[@"color"] = ntkInspectUIColor(label.textColor) ?: NSNull.null;
    }
    if ([view isKindOfClass:UIImageView.class]) {
        UIImage *image = ((UIImageView *)view).image;
        row[@"imageSize"] = @[@(image.size.width), @(image.size.height)];
        row[@"imageScale"] = @(image.scale);
        row[@"contentMode"] = @(view.contentMode);
    }
    NSMutableArray *children = NSMutableArray.array;
    for (UIView *child in view.subviews) [children addObject:views(child, root, depth + 1)];
    row[@"children"] = children;
    return row;
}
@interface NativeOptionTableSource : NSObject <UITableViewDataSource, UITableViewDelegate>
@property(nonatomic, strong) UITableViewCell *cell;
@property(nonatomic) CGFloat height;
@end
@implementation NativeOptionTableSource
- (NSInteger)tableView:(UITableView *)table numberOfRowsInSection:(NSInteger)section {
    (void)table; (void)section; return 1;
}
- (UITableViewCell *)tableView:(UITableView *)table cellForRowAtIndexPath:(NSIndexPath *)path {
    (void)table; (void)path; return self.cell;
}
- (CGFloat)tableView:(UITableView *)table heightForRowAtIndexPath:(NSIndexPath *)path {
    (void)table; (void)path; return self.height;
}
@end
static int capture(UIWindow *window) { @autoreleasepool {
    if (probeArgc != 4 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    NSString *directory = @(probeArgv[1]), *requested = @(probeArgv[2]);
    NSInteger requestedMode = [@(probeArgv[3]) integerValue];
    if ([NSFileManager.defaultManager fileExistsAtPath:directory] ||
        ![NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil]) return 2;
    [NSUserDefaults.standardUserDefaults setVolatileDomain:@{
        @"AppleLanguages":@[@"en-US"], @"AppleLocale":@"en_US"} forName:NSArgumentDomain];
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW) ||
        !dlopen("/System/Library/PrivateFrameworks/NanoTimeKitCompanion.framework/NanoTimeKitCompanion", RTLD_NOW)) return 3;
    id device = get(NSClassFromString(@"CLKDevice"), @"currentDevice");
    __block BOOL found = NO;
    void (^observe)(id) = ^(id bundle) { @try {
        id face = ((id (*)(id, SEL, id))objc_msgSend)(bundle, NSSelectorFromString(@"defaultFaceForDevice:"), device);
        NSDictionary *json = face ? get(face, @"JSONObjectRepresentation") : nil;
        NSString *family = json[@"bundle id"] ? [@"bundle:" stringByAppendingString:json[@"bundle id"]]
            : json[@"face type"] ? [@"type:" stringByAppendingString:json[@"face type"]] : nil;
        if (found || ![family isEqual:requested] || json[@"customData"] || json[@"resource directory"] || get(face, @"resourceDirectory")) return;
        for (NSString *slot in get(face, @"_complicationSlotDescriptors"))
            ((void (*)(id, SEL, id, id))objc_msgSend)(face, NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
        __attribute__((objc_precise_lifetime)) id faceController = ((id (*)(id, SEL, id, id))objc_msgSend)(
            get(NSClassFromString(@"NTKFaceViewController"), @"alloc"), NSSelectorFromString(@"initWithFace:configuration:"), face, nil);
        for (id collection in get(face, @"editOptionsForCustomEditModes")) {
            if (number(collection, @"mode") != (NSUInteger)requestedMode || number(collection, @"collectionType") != 0 || get(collection, @"slot")) continue;
            __attribute__((objc_precise_lifetime)) id controller = ((id (*)(id, SEL, id, id, BOOL, id, id))objc_msgSend)(
                get(NSClassFromString(@"NTKCFaceDetailEditOptionSectionController"), @"alloc"),
                NSSelectorFromString(@"initWithTableViewController:face:inGallery:editOptionCollection:faceView:"),
                nil, face, YES, collection, get(faceController, @"faceView"));
            UITableViewCell *cell = get(controller, @"cell");
            CGFloat height = ((CGFloat (*)(id, SEL))objc_msgSend)(cell, NSSelectorFromString(@"rowHeight"));
            UITableView *table = [[UITableView alloc] initWithFrame:CGRectMake(0, 100, 393, 400) style:UITableViewStyleInsetGrouped];
            table.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
            table.separatorStyle = UITableViewCellSeparatorStyleNone;
            table.sectionHeaderTopPadding = 0;
            NativeOptionTableSource *source = NativeOptionTableSource.new;
            source.cell = cell; source.height = height;
            table.dataSource = source; table.delegate = source;
            [window.rootViewController.view addSubview:table];
            [table reloadData]; layoutTree(table);
            NSUInteger index = 0; activateOptions(cell, &index); layoutTree(table);
            if (!cell.window || !ntkInspectSnapshotLayers(cell, directory, @"row")) return;
            NSData *data = [NSJSONSerialization dataWithJSONObject:@{@"family":family, @"mode":@(requestedMode),
                @"title":get(collection, @"localizedName") ?: @"", @"rowHeight":@(height),
                @"windowAttached":@YES, @"cellInTable":ntkInspectRect(cell.frame),
                @"views":views(cell, cell, 0)} options:NSJSONWritingPrettyPrinted error:nil];
            if (!data || ![data writeToFile:[directory stringByAppendingPathComponent:@"geometry.json"] atomically:YES]) return;
            UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:cell.bounds.size];
            UIImage *image = [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
                [cell.layer renderInContext:context.CGContext];
            }];
            NSData *png = UIImagePNGRepresentation(image);
            found = [png writeToFile:[directory stringByAppendingPathComponent:@"row.png"] atomically:YES];
        }
    } @catch (NSException *error) { fprintf(stderr, "%s: %s\n", error.name.UTF8String, error.reason.UTF8String); }};
    ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(get(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager"),
        NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
    return found ? 0 : 4;
} }
@interface NativeOptionAppDelegate : UIResponder <UIApplicationDelegate>
@end
@implementation NativeOptionAppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    (void)application; (void)options; return YES;
}
@end
@interface NativeOptionSceneDelegate : UIResponder <UIWindowSceneDelegate>
@property(nonatomic, strong) UIWindow *window;
@end
@implementation NativeOptionSceneDelegate
- (void)scene:(UIScene *)scene willConnectToSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    (void)session; (void)options;
    if (![scene isKindOfClass:UIWindowScene.class]) exit(3);
    self.window = [[UIWindow alloc] initWithWindowScene:(UIWindowScene *)scene];
    self.window.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
    self.window.rootViewController = UIViewController.new;
    [self.window makeKeyAndVisible];
    dispatch_async(dispatch_get_main_queue(), ^{ int result = capture(self.window); fflush(stdout); fflush(stderr); exit(result); });
}
@end
int main(int argc, char *argv[]) { @autoreleasepool {
    probeArgc = argc; probeArgv = argv;
    return UIApplicationMain(argc, argv, nil, NSStringFromClass(NativeOptionAppDelegate.class));
} }
