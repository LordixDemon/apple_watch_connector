#import <UIKit/UIKit.h>
#import <objc/message.h>
#import <dlfcn.h>
#import <CommonCrypto/CommonDigest.h>

// Simulator-only native action artwork. No face, library, preferences or transport.
int main(int argc, char *argv[]) { @autoreleasepool {
    if (argc != 2 || ![NSProcessInfo.processInfo.environment[@"SIMULATOR_ROOT"] length]) return 2;
    NSString *directory = @(argv[1]);
    if ([NSFileManager.defaultManager fileExistsAtPath:directory] ||
        ![NSFileManager.defaultManager createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil]) return 2;
    if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW) ||
        !dlopen("/System/Library/PrivateFrameworks/NanoTimeKitCompanion.framework/NanoTimeKitCompanion", RTLD_NOW)) return 3;
    Class type = NSClassFromString(@"NTKCFaceDetailPigmentEditOptionCell");
    if (!type || ![type isSubclassOfClass:UITableViewCell.class]) return 3;
    UITableViewCell *cell = [[type alloc] initWithStyle:UITableViewCellStyleDefault reuseIdentifier:nil];
    cell.overrideUserInterfaceStyle = UIUserInterfaceStyleDark;
    NSMutableArray *rows = NSMutableArray.array;
    @try {
        for (NSString *name in @[@"_plusImage", @"_dividerImage"]) {
            SEL selector = NSSelectorFromString(name);
            if (![cell respondsToSelector:selector]) return 3;
            UIImage *image = ((id (*)(id, SEL))objc_msgSend)(cell, selector);
            if (![image isKindOfClass:UIImage.class] || image.size.width <= 0 || image.size.height <= 0 ||
                image.size.width > 96 || image.size.height > 96) return 4;
            NSData *data = UIImagePNGRepresentation(image);
            if (!data || data.length > 256 * 1024) return 4;
            unsigned char digest[CC_SHA256_DIGEST_LENGTH];
            CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
            NSMutableString *hash = NSMutableString.string;
            for (NSUInteger i = 0; i < sizeof(digest); i++) [hash appendFormat:@"%02x", digest[i]];
            NSString *leaf = [hash stringByAppendingString:@".png"];
            if (![data writeToFile:[directory stringByAppendingPathComponent:leaf] atomically:YES]) return 4;
            [rows addObject:@{@"selector":name,@"image":leaf,@"width":@(image.size.width),
                @"height":@(image.size.height),@"scale":@(image.scale),@"renderingMode":@(image.renderingMode)}];
        }
    } @catch (NSException *error) {
        fprintf(stderr, "%s: %s\n", error.name.UTF8String, error.reason.UTF8String); return 5;
    }
    // Nil inputs keep initialization transient while exercising the original
    // parent's font/layout path. No face is created or loaded.
    SEL initializer = NSSelectorFromString(@"initWithCollection:forFaceView:face:");
    id owner = ((id (*)(id, SEL, id, id, id))objc_msgSend)([type alloc], initializer, nil, nil, nil);
    UICollectionViewFlowLayout *layout = ((id (*)(id, SEL))objc_msgSend)(owner, NSSelectorFromString(@"layout"));
    if (![layout isKindOfClass:UICollectionViewFlowLayout.class]) return 4;
    CGRect swatch = ((CGRect (*)(id, SEL))objc_msgSend)(owner, NSSelectorFromString(@"swatchFrame"));
    NSDictionary *geometry = @{@"itemWidth":@(layout.itemSize.width),@"itemHeight":@(layout.itemSize.height),
        @"lineSpacing":@(layout.minimumLineSpacing),@"interitemSpacing":@(layout.minimumInteritemSpacing),
        @"swatchFrame":@[@(swatch.origin.x),@(swatch.origin.y),@(swatch.size.width),@(swatch.size.height)]};
    NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"version":@1,
        @"runtime":UIDevice.currentDevice.systemVersion,@"cellClass":NSStringFromClass(type),@"rows":rows,@"geometry":geometry}
        options:NSJSONWritingPrettyPrinted error:nil];
    if (!json || ![json writeToFile:[directory stringByAppendingPathComponent:@"capture.json"] atomically:YES]) return 4;
    fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout);
    return 0;
} }
