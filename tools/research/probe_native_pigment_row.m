#import <UIKit/UIKit.h>
#import <objc/message.h>
#import <dlfcn.h>

// Simulator-only transient header and empty color collection. No face, library,
// preference, pairing or transport. Capture layout rather than reproducing it.
static id get(id object, NSString *name) {
    SEL selector = NSSelectorFromString(name);
    return [object respondsToSelector:selector] ? ((id (*)(id, SEL))objc_msgSend)(object, selector) : nil;
}
static void set(id object, NSString *name, id value) {
    ((void (*)(id, SEL, id))objc_msgSend)(object, NSSelectorFromString(name), value);
}
static NSArray *rect(CGRect value) {
    return @[@(value.origin.x),@(value.origin.y),@(value.size.width),@(value.size.height)];
}
static NSArray *insets(UIEdgeInsets value) {
    return @[@(value.top),@(value.left),@(value.bottom),@(value.right)];
}
static UICollectionView *collectionView(UIView *view) {
    if ([view isKindOfClass:UICollectionView.class]) return (UICollectionView *)view;
    for (UIView *child in view.subviews) {
        UICollectionView *found = collectionView(child);
        if (found) return found;
    }
    return nil;
}
static void labels(UIView *view, UIView *root, NSMutableArray *rows) {
    if ([view isKindOfClass:UILabel.class]) {
        UILabel *label = (UILabel *)view;
        CGFloat red = 0, green = 0, blue = 0, alpha = 0;
        UIColor *color = [label.textColor resolvedColorWithTraitCollection:label.traitCollection];
        if (![color getRed:&red green:&green blue:&blue alpha:&alpha]) abort();
        [rows addObject:@{@"text":label.text ?: @"",@"frame":rect([label convertRect:label.bounds toView:root]),
            @"pointSize":@(label.font.pointSize),@"lineHeight":@(label.font.lineHeight),
            @"ascender":@(label.font.ascender),@"descender":@(label.font.descender),
            @"symbolicTraits":@(label.font.fontDescriptor.symbolicTraits),
            @"rgba":@[@(red),@(green),@(blue),@(alpha)],
            @"alignment":@(label.textAlignment),@"numberOfLines":@(label.numberOfLines)}];
    }
    for (UIView *child in view.subviews) labels(child, root, rows);
}
@interface ProbeTableSource : NSObject <UITableViewDataSource, UITableViewDelegate>
@property(nonatomic, strong) UITableViewCell *cell;
@property(nonatomic, strong) UIView *header;
@property(nonatomic) CGFloat rowHeight, headerHeight;
@end
@implementation ProbeTableSource
- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    (void)tableView; (void)section; return 1;
}
- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)path {
    (void)tableView; (void)path; return self.cell;
}
- (CGFloat)tableView:(UITableView *)tableView heightForRowAtIndexPath:(NSIndexPath *)path {
    (void)tableView; (void)path; return self.rowHeight;
}
- (UIView *)tableView:(UITableView *)tableView viewForHeaderInSection:(NSInteger)section {
    (void)tableView; (void)section; return self.header;
}
- (CGFloat)tableView:(UITableView *)tableView heightForHeaderInSection:(NSInteger)section {
    (void)tableView; (void)section; return self.headerHeight;
}
@end
static int captureRow(int argc, char *argv[]) { @autoreleasepool {
    BOOL application = [NSProcessInfo.processInfo.environment[@"NATIVE_ROW_APPLICATION"] isEqual:@"1"];
    if (argc != (application ? 2 : 1) || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    (void)argv;
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW) ||
        !dlopen("/System/Library/PrivateFrameworks/NanoTimeKitCompanion.framework/NanoTimeKitCompanion", RTLD_NOW)) return 3;
    Class headerType = NSClassFromString(@"NTKCFaceDetailSectionHeaderView");
    Class cellType = NSClassFromString(@"NTKCFaceDetailPigmentEditOptionCell");
    Class collectionType = NSClassFromString(@"NTKEditOptionCollection");
    if (!headerType || !cellType || !collectionType) return 3;
    @try {
        CGFloat headerHeight = ((CGFloat (*)(id, SEL))objc_msgSend)(headerType, NSSelectorFromString(@"headerHeight"));
        UIView *header = [[headerType alloc] init];
        header.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
        set(header, @"setTitle:", @"Color");
        set(header, @"setSubtitle:", @"Multicolor");
        header.frame = CGRectMake(0, 0, 393, headerHeight);
        [header setNeedsLayout]; [header layoutIfNeeded];
        NSMutableArray *headerLabels = NSMutableArray.array;
        labels(header, header, headerLabels);
        id collection = ((id (*)(id, SEL, NSInteger, id, id, NSInteger))objc_msgSend)([collectionType alloc],
            NSSelectorFromString(@"initWithEditMode:localizedName:options:collectionType:"), 10, @"Color", @[], 1);
        UITableViewCell *cell = ((id (*)(id, SEL, id, id, id))objc_msgSend)([cellType alloc],
            NSSelectorFromString(@"initWithCollection:forFaceView:face:"), collection, nil, nil);
        cell.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
        CGFloat height = ((CGFloat (*)(id, SEL))objc_msgSend)(cell, NSSelectorFromString(@"rowHeight"));
        cell.frame = CGRectMake(0, 0, 393, height);
        [cell setNeedsLayout]; [cell layoutIfNeeded];
        CGRect swatch = ((CGRect (*)(id, SEL))objc_msgSend)(cell, NSSelectorFromString(@"swatchFrame"));
        id optionsDescription = get(NSClassFromString(@"NTKPigmentEditOption"), @"optionsDescription");
        NSMutableArray *cellLabels = NSMutableArray.array;
        labels(cell, cell, cellLabels);
        UICollectionView *colors = collectionView(cell);
        if (!colors) return 4;
        NSDictionary *standalone = @{@"separatorInset":insets(cell.separatorInset),
            @"collectionFrame":rect(colors.frame),@"contentInset":insets(colors.contentInset)};
        // Source viewDidLoad uses insetGrouped and the native screen margin.
        CGFloat (*margin)(void) = dlsym(RTLD_DEFAULT, "NTKCScreenEdgeMargin");
        if (!margin) return 4;
        UITableView *table = [[UITableView alloc] initWithFrame:CGRectMake(0,0,393,400)
            style:UITableViewStyleInsetGrouped];
        table.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
        table.separatorStyle = UITableViewCellSeparatorStyleNone;
        table.separatorInset = UIEdgeInsetsMake(0,margin(),0,0);
        table.sectionHeaderTopPadding = 0;
        ProbeTableSource *source = ProbeTableSource.new;
        source.cell = cell; source.header = header;
        source.rowHeight = height; source.headerHeight = headerHeight;
        table.dataSource = source; table.delegate = source;
        if (application) {
            for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
                UIWindow *window = get(scene.delegate, @"window");
                if (window) { [window.rootViewController.view addSubview:table]; break; }
            }
            if (!table.window) return 4;
        }
        [table reloadData]; [table setNeedsLayout]; [table layoutIfNeeded];
        [cell setNeedsLayout]; [cell layoutIfNeeded];
        [header setNeedsLayout]; [header layoutIfNeeded];
        [headerLabels removeAllObjects]; labels(header, header, headerLabels);
        NSDictionary *result = @{@"runtime":UIDevice.currentDevice.systemVersion,@"headerHeight":@(headerHeight),
            @"headerLabels":headerLabels,@"collectionType":@1,@"mode":@10,@"rowHeight":@(height),
            @"swatchFrame":rect(swatch),@"cellLabels":cellLabels,
            @"separatorInset":insets(cell.separatorInset),
            @"collectionFrame":rect(colors.frame),@"contentInset":insets(colors.contentInset),
            @"standalone":standalone,@"tableStyle":@(table.style),@"nativeScreenMargin":@(margin()),
            @"tableCellFrame":rect(cell.frame),@"tableHeaderFrame":rect(header.frame),
            @"tableCellInTable":rect([cell convertRect:cell.bounds toView:table]),
            @"windowAttached":table.window ? @YES : @NO,
            @"pigmentClassOptionsDescription":optionsDescription ?: NSNull.null};
        NSData *json = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:nil];
        if (!json) return 4;
        if (application) {
            NSString *path = @(argv[1]);
            if ([NSFileManager.defaultManager fileExistsAtPath:path] || ![json writeToFile:path atomically:YES]) return 4;
            fprintf(stdout,"{\"capture\":\"native-row.json\"}\n");
        } else { fwrite(json.bytes, 1, json.length, stdout);fputc('\n',stdout); }
    } @catch (NSException *error) {
        fprintf(stderr,"%s: %s\n",error.name.UTF8String,error.reason.UTF8String);return 5;
    }
    return 0;
} }

static int probeArgc;
static char **probeArgv;
@interface NativeRowAppDelegate : UIResponder <UIApplicationDelegate>
@end
@implementation NativeRowAppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    (void)application; (void)options; return YES;
}
@end
@interface NativeRowSceneDelegate : UIResponder <UIWindowSceneDelegate>
@property(nonatomic, strong) UIWindow *window;
@end
@implementation NativeRowSceneDelegate
- (void)scene:(UIScene *)scene willConnectToSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    (void)session; (void)options;
    if (![scene isKindOfClass:UIWindowScene.class]) exit(3);
    self.window = [[UIWindow alloc] initWithWindowScene:(UIWindowScene *)scene];
    self.window.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
    self.window.rootViewController = UIViewController.new;
    [self.window makeKeyAndVisible];
    dispatch_async(dispatch_get_main_queue(), ^{
        int result = captureRow(probeArgc, probeArgv);
        fflush(stdout); fflush(stderr); exit(result);
    });
}
@end
int main(int argc, char *argv[]) { @autoreleasepool {
    if ([NSProcessInfo.processInfo.environment[@"NATIVE_ROW_APPLICATION"] isEqual:@"1"]) {
        probeArgc = argc; probeArgv = argv;
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(NativeRowAppDelegate.class));
    }
    return captureRow(argc, argv);
} }
