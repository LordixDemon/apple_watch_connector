#import <UIKit/UIKit.h>
#import <objc/message.h>
#import <dlfcn.h>

// Simulator-only geometry. Never loads a face, preferences, pairing or transport.
static id get(id object, NSString *name) {
    SEL selector = NSSelectorFromString(name);
    return [object respondsToSelector:selector] ? ((id (*)(id, SEL))objc_msgSend)(object, selector) : nil;
}

@interface LayoutData : NSObject <UICollectionViewDataSource>
@end
@implementation LayoutData
- (NSInteger)numberOfSectionsInCollectionView:(UICollectionView *)view { return 2; }
- (NSInteger)collectionView:(UICollectionView *)view numberOfItemsInSection:(NSInteger)section { return 20; }
- (UICollectionViewCell *)collectionView:(UICollectionView *)view cellForItemAtIndexPath:(NSIndexPath *)path {
    return [view dequeueReusableCellWithReuseIdentifier:@"cell" forIndexPath:path];
}
- (UICollectionReusableView *)collectionView:(UICollectionView *)view viewForSupplementaryElementOfKind:(NSString *)kind atIndexPath:(NSIndexPath *)path {
    UICollectionReusableView *header = [view dequeueReusableSupplementaryViewOfKind:kind withReuseIdentifier:@"header" forIndexPath:path];
    ((void (*)(id, SEL, id))objc_msgSend)(header, NSSelectorFromString(@"setName:"), @"Layout reference");
    return header;
}
@end

int main(void) { @autoreleasepool {
    if (![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
    Class type = NSClassFromString(@"_NTKPigmentAddController");
    Class header = NSClassFromString(@"_NTKPigmentAddHeaderView");
    if (!type || !header) return 3;
    id owner = [[type alloc] init];
    UICollectionViewCompositionalLayout *layout = get(owner, @"_collectionViewLayout");
    if (![layout isKindOfClass:UICollectionViewCompositionalLayout.class]) return 3;
    LayoutData *data = LayoutData.new;
    UICollectionView *view = [[UICollectionView alloc] initWithFrame:CGRectMake(0, 0, 393, 800) collectionViewLayout:layout];
    [view registerClass:UICollectionViewCell.class forCellWithReuseIdentifier:@"cell"];
    [view registerClass:header forSupplementaryViewOfKind:UICollectionElementKindSectionHeader withReuseIdentifier:@"header"];
    view.dataSource = data;
    [view reloadData];
    [view layoutIfNeeded];
    NSMutableArray *frames = NSMutableArray.array;
    for (NSUInteger section = 0; section < 2; section++) {
        for (NSUInteger item = 0; item < 3; item++) {
            UICollectionViewLayoutAttributes *a = [layout layoutAttributesForItemAtIndexPath:[NSIndexPath indexPathForItem:item inSection:section]];
            if (!a) return 4;
            [frames addObject:@{@"section":@(section), @"item":@(item),
                @"frame":@[@(a.frame.origin.x), @(a.frame.origin.y), @(a.frame.size.width), @(a.frame.size.height)]}];
        }
    }
    NSArray *categories = @[UIContentSizeCategoryExtraSmall, UIContentSizeCategorySmall,
        UIContentSizeCategoryMedium, UIContentSizeCategoryLarge, UIContentSizeCategoryExtraLarge,
        UIContentSizeCategoryExtraExtraLarge, UIContentSizeCategoryExtraExtraExtraLarge,
        UIContentSizeCategoryAccessibilityMedium, UIContentSizeCategoryAccessibilityLarge,
        UIContentSizeCategoryAccessibilityExtraLarge, UIContentSizeCategoryAccessibilityExtraExtraLarge,
        UIContentSizeCategoryAccessibilityExtraExtraExtraLarge];
    NSMutableArray *fonts = NSMutableArray.array;
    SEL fontSelector = NSSelectorFromString(@"_fontWithSizeCategory:");
    if (![header respondsToSelector:fontSelector]) return 3;
    for (NSString *category in categories) {
        UIFont *font = ((id (*)(id, SEL, id))objc_msgSend)(header, fontSelector, category);
        if (![font isKindOfClass:UIFont.class]) return 4;
        [fonts addObject:@{@"category":category,@"pointSize":@(font.pointSize),
            @"lineHeight":@(font.lineHeight),@"symbolicTraits":@(font.fontDescriptor.symbolicTraits)}];
    }
    NSDictionary *result = @{@"runtime":UIDevice.currentDevice.systemVersion,
        @"controllerClass":NSStringFromClass(type),@"headerClass":NSStringFromClass(header),
        @"screenPixels":@[@(UIScreen.mainScreen.currentMode.size.width),@(UIScreen.mainScreen.currentMode.size.height)],
        @"interSectionSpacing":@(layout.configuration.interSectionSpacing),@"frames":frames,@"fonts":fonts};
    NSData *json = [NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:nil];
    if (!json) return 4;
    fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout);
    return 0;
} }
