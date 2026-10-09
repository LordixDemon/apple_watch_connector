#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>

// Read-only simulator preview probe. Uses NanoTimeKit's actual snapshot service;
// no paired Watch, face collection mutation, transport or synthesized rendering.
static id call0(id object, NSString *selector) {
    return ((id (*)(id, SEL))objc_msgSend)(object, NSSelectorFromString(selector));
}
int main(int argc, const char **argv) {
    @autoreleasepool {
        BOOL record = argc == 4 && strcmp(argv[1], "--snapshot-record") == 0;
        BOOL configured = argc == 4 && (strcmp(argv[1], "--configuration") == 0 || record);
        if (argc != 3 && !configured) {
            fprintf(stderr, "usage: probe-native-face-snapshot bundle output.png | --all directory | --configuration face.json output.png | --snapshot-record face.json NEW-temp-directory\n");
            return 2;
        }
        NSDictionary *requested = nil;
        if (configured) {
            NSData *data = [NSData dataWithContentsOfFile:[NSString stringWithUTF8String:argv[2]]];
            NSError *error = nil;
            id parsed = data.length && data.length <= 131072
                ? [NSJSONSerialization JSONObjectWithData:data options:0 error:&error] : nil;
            if (![parsed isKindOfClass:[NSDictionary class]] || parsed[@"customData"]
                    || parsed[@"resource directory"] || ![parsed[@"customization"] isKindOfClass:[NSDictionary class]]) {
                fprintf(stderr, "Unsupported or invalid preview configuration\n"); return 6;
            }
            requested = parsed;
        }
        if (!dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit", RTLD_NOW)) return 3;
        id device = call0(NSClassFromString(@"CLKDevice"), @"currentDevice");
        id manager = call0(NSClassFromString(@"NTKFaceBundleManager"), @"sharedManager");
        NSMutableDictionary *faces = [NSMutableDictionary dictionary];
        NSString *identifier = [NSString stringWithUTF8String:argv[1]];
        BOOL all = [identifier isEqual:@"--all"];
        void (^observe)(id) = ^(id bundle) {
            if (all || configured || [call0([bundle class], @"identifier") isEqual:identifier]) {
                id selected = ((id (*)(id, SEL, id))objc_msgSend)(bundle,
                    NSSelectorFromString(@"defaultFaceForDevice:"), device);
                if (!selected) return;
                NSString *title = ((id (*)(id, SEL, id))objc_msgSend)(bundle,
                    NSSelectorFromString(@"galleryTitleForDevice:"), device);
                if (!title.length || call0(selected, @"resourceDirectory")) return;
                NSDictionary *slots = call0(selected, @"_complicationSlotDescriptors");
                for (NSString *slot in slots) {
                    ((void (*)(id, SEL, id, id))objc_msgSend)(selected,
                        NSSelectorFromString(@"setComplication:forSlot:"), nil, slot);
                }
                NSDictionary *config = call0(selected, @"JSONObjectRepresentation");
                if (config[@"customData"] || config[@"resource directory"] || config[@"complications"]) return;
                NSString *family = config[@"bundle id"] ? [@"bundle:" stringByAppendingString:config[@"bundle id"]]
                    : [@"type:" stringByAppendingString:config[@"face type"]];
                if (configured) {
                    NSString *requestedFamily = requested[@"bundle id"]
                        ? [@"bundle:" stringByAppendingString:requested[@"bundle id"]]
                        : [@"type:" stringByAppendingString:requested[@"face type"] ?: @""];
                    if (![family isEqual:requestedFamily]) return;
                    id configuration = ((id (*)(id, SEL, id, id, id))objc_msgSend)(
                        call0(NSClassFromString(@"NTKFaceConfiguration"), @"alloc"),
                        NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"), requested, selected, device);
                    if (!configuration) return;
                    ((void (*)(id, SEL, id))objc_msgSend)(selected, NSSelectorFromString(@"applyConfiguration:"), configuration);
                    NSDictionary *actual = call0(selected, @"JSONObjectRepresentation");
                    if (![actual[@"customization"] isEqual:requested[@"customization"]]
                            || ![(actual[@"complications"] ?: @{}) isEqual:(requested[@"complications"] ?: @{})]) {
                        fprintf(stderr, "Native renderer normalized requested customization or complications; refusing substitute preview\n");
                        return;
                    }
                }
                faces[family] = selected;
            }
        };
        ((void (*)(id, SEL, id, BOOL, id))objc_msgSend)(manager,
            NSSelectorFromString(@"enumerateFaceBundlesOnDevice:includingLegacy:withBlock:"), device, YES, observe);
        if (!faces.count) return 4;
        id client = call0(NSClassFromString(@"NTKFaceSnapshotClient"), @"sharedInstance");
        NSString *destination = [NSString stringWithUTF8String:argv[configured ? 3 : 2]];
        if (record) {
            NSString *normalized = destination.stringByStandardizingPath;
            if ((! [normalized hasPrefix:@"/tmp/"] && ![normalized hasPrefix:@"/private/tmp/"])
                    || [[NSFileManager defaultManager] fileExistsAtPath:normalized]
                    || ![[NSFileManager defaultManager] createDirectoryAtPath:normalized
                        withIntermediateDirectories:NO attributes:nil error:nil]) {
                fprintf(stderr, "Snapshot record needs a new absolute temporary directory\n");
                return 7;
            }
            destination = normalized;
        }
        if (all) [[NSFileManager defaultManager] createDirectoryAtPath:destination
            withIntermediateDirectories:YES attributes:nil error:nil];
        NSUInteger failures = 0;
        for (NSString *family in [[faces allKeys] sortedArrayUsingSelector:@selector(compare:)]) {
        id selected = faces[family];
        NSString *filename = [[family componentsSeparatedByCharactersInSet:
            [[NSCharacterSet characterSetWithCharactersInString:@"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"] invertedSet]]
            componentsJoinedByString:@"_"];
        NSString *path = record ? [destination stringByAppendingPathComponent:@"snapshot.png"]
            : all ? [destination stringByAppendingPathComponent:[filename stringByAppendingString:@".png"]] : destination;
        __block BOOL finished = NO, saved = NO;
        void (^completion)(id, id) = ^(id result, id error) {
            fprintf(stderr, "%s result=%s error=%s\n", family.UTF8String, NSStringFromClass([result class]).UTF8String,
                [[error description] UTF8String] ?: "none");
            id image = [result respondsToSelector:NSSelectorFromString(@"snapshot")]
                ? call0(result, @"snapshot") : result;
            NSData *(*png)(id) = dlsym(RTLD_DEFAULT, "UIImagePNGRepresentation");
            if (image && png && [image isKindOfClass:NSClassFromString(@"UIImage")]) {
                NSData *data = png(image);
                saved = [data writeToFile:path atomically:YES];
            }
            if (record && saved) {
                NSError *replicationError = nil;
                id stored = ((id (*)(id, SEL, id, NSError **))objc_msgSend)(result,
                    NSSelectorFromString(@"_snapshotResultForGalleryLiteStoredWithin:withError:"),
                    [NSURL fileURLWithPath:destination isDirectory:YES], &replicationError);
                NSData *archive = stored ? [NSKeyedArchiver archivedDataWithRootObject:stored
                    requiringSecureCoding:YES error:&replicationError] : nil;
                BOOL archiveSaved = archive.length && archive.length <= 4194304
                    && [archive writeToFile:[destination stringByAppendingPathComponent:@"snapshot-result.bplist"] atomically:YES];
                NSMutableDictionary *methods = [NSMutableDictionary dictionary];
                for (NSString *name in @[@"NTKLibrarySnapshotReplicator", @"NTKGallerySnapshotReplicator",
                        @"NTKReplicatedSnapshotListenerService", @"NTKSnapshotReplicatorService"]) {
                    Class cls = NSClassFromString(name);
                    if (!cls) continue;
                    NSMutableArray *entries = [NSMutableArray array];
                    unsigned int count = 0;
                    Method *declared = class_copyMethodList(cls, &count);
                    for (unsigned int i = 0; i < count && i < 256; i++) {
                        [entries addObject:@{@"selector": NSStringFromSelector(method_getName(declared[i])),
                            @"encoding": [NSString stringWithUTF8String:method_getTypeEncoding(declared[i])] ?: @""}];
                    }
                    free(declared);
                    methods[name] = entries;
                }
                id cachedFile = stored ? call0(stored, @"cachedFile") : nil;
                NSDictionary *report = @{@"family": family,
                    @"configuration": call0(selected, @"JSONObjectRepresentation") ?: @{},
                    @"snapshotKey": call0(result, @"snapshotKey") ?: @"",
                    @"rawSnapshotKey": call0(result, @"rawSnapshotKey") ?: @"",
                    @"cachedFile": cachedFile ? [call0(cachedFile, @"fileURL") path] ?: @"" : @"",
                    @"fileFormat": cachedFile ? @(((NSUInteger (*)(id, SEL))objc_msgSend)(cachedFile,
                        NSSelectorFromString(@"fileFormat"))) : @0,
                    @"archiveSaved": @(archiveSaved), @"archiveBytes": @(archive.length),
                    @"replicationError": replicationError.description ?: @"",
                    @"replicationMethods": methods};
                NSData *metadata = [NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted error:nil];
                BOOL metadataSaved = metadata.length && metadata.length <= 262144
                    && [metadata writeToFile:[destination stringByAppendingPathComponent:@"record.json"] atomically:YES];
                // GalleryLite prepares/cleans its destination before writing.
                // Persist the independent PNG after that native operation.
                NSData *referencePNG = png && image ? png(image) : nil;
                BOOL referenceSaved = referencePNG.length && referencePNG.length <= 4194304
                    && [referencePNG writeToFile:path atomically:YES];
                saved = saved && archiveSaved && metadataSaved && referenceSaved;
            }
            finished = YES;
        };
        NSDictionary *options = call0(NSClassFromString(@"NTKFaceSnapshotter"), @"defaultModernSnapshotOptions") ?: @{};
        ((void (*)(id, SEL, id, id, id))objc_msgSend)(client,
            NSSelectorFromString(record ? @"requestSnapshotOfFace:options:completion:"
                : @"snapshotFace:options:completion:"), selected, options, completion);
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:25];
        while (!finished && deadline.timeIntervalSinceNow > 0) {
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
        }
        if (!saved) failures++;
        }
        fprintf(stderr, "Snapshot profiles=%lu failures=%lu\n", faces.count, failures);
        return failures ? 5 : 0;
    }
}
