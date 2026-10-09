#import <Foundation/Foundation.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <dlfcn.h>
#import <CoreGraphics/CoreGraphics.h>

// Research only: native encoders and validators, no Watch transport or pairing.
static id msg(id o, NSString *s) {
    return ((id (*)(id,SEL))objc_msgSend)(o, NSSelectorFromString(s));
}
static id arg(id o, NSString *s, id a) {
    return ((id (*)(id,SEL,id))objc_msgSend)(o, NSSelectorFromString(s), a);
}
int main(int argc, char **argv) {
    @autoreleasepool { @try {
        dlopen("/System/Library/PrivateFrameworks/NanoTimeKit.framework/NanoTimeKit",RTLD_NOW);
        const char *path="/System/Library/NanoTimeKit/FaceBundles/NTKParmesanFaceBundle.bundle/NTKParmesanFaceBundle";
        if (!dlopen(path,RTLD_NOW)) { fprintf(stderr,"%s\n",dlerror()); return 1; }
        id bundle=msg(NSClassFromString(@"NTKParmesanFaceBundle"),@"new");
        id device=msg(NSClassFromString(@"CLKDevice"),@"currentDevice");
        id face=arg(bundle,@"defaultFaceForDevice:",device);
        NSMutableDictionary *result=[NSMutableDictionary dictionary];
        if (argc>2 && [@(argv[1]) isEqual:@"--validate"]) {
            NSString *root=@(argv[2]);
            NSString *directory=[root stringByAppendingPathComponent:@"Resources"];
            id manifest=arg(NSClassFromString(@"NTKParmesanResourcesManifest"),@"manifestForResourceDirectory:",directory);
            NSError *error=nil;
            result[@"valid"]=@(((BOOL (*)(id,SEL,NSError **))objc_msgSend)(manifest,NSSelectorFromString(@"validateManifestWithError:"),&error));
            result[@"error"]=error.description ?: @"";
            id reader=arg(NSClassFromString(@"NTKParmesanAssetReader"),@"readerForResourceDirectory:",directory);
            result[@"count"]=@(((NSUInteger (*)(id,SEL))objc_msgSend)(reader,NSSelectorFromString(@"count")));
            result[@"asset"]=msg(msg(reader,@"firstObject"),@"asDictionary") ?: @{};
            NSData *json=[NSData dataWithContentsOfFile:[root stringByAppendingPathComponent:@"face.json"]];
            NSDictionary *configuration=[NSJSONSerialization JSONObjectWithData:json options:0 error:&error];
            id parsed=((id (*)(id,SEL,id,id,id))objc_msgSend)(msg(NSClassFromString(@"NTKFaceConfiguration"),@"alloc"),
                NSSelectorFromString(@"initWithJSONDictionary:editModeMapping:forDevice:"),configuration,face,device);
            ((void (*)(id,SEL,id))objc_msgSend)(face,NSSelectorFromString(@"applyConfiguration:"),parsed);
            ((void (*)(id,SEL,id))objc_msgSend)(face,NSSelectorFromString(@"setResourceDirectory:"),directory);
            result[@"configuration"]=msg(face,@"JSONObjectRepresentation") ?: @{};
            result[@"addable"]=@(((BOOL (*)(id,SEL))objc_msgSend)(face,NSSelectorFromString(@"isValidConfigurationToAddToLibrary")));
            if (argc>3) {
                __block BOOL finished=NO;
                void (^completion)(id,id)=^(id snapshot,id error) {
                    id image=msg(snapshot,@"snapshot");
                    NSData *(*png)(id)=dlsym(RTLD_DEFAULT,"UIImagePNGRepresentation");
                    result[@"snapshotSaved"]=@(image && png && [png(image) writeToFile:@(argv[3]) atomically:YES]);
                    result[@"snapshotError"]=[error description] ?: @"";
                    finished=YES;
                };
                ((void (*)(id,SEL,id,id,id))objc_msgSend)(msg(NSClassFromString(@"NTKFaceSnapshotClient"),@"sharedInstance"),
                    NSSelectorFromString(@"snapshotFace:options:completion:"),face,msg(NSClassFromString(@"NTKFaceSnapshotter"),@"defaultModernSnapshotOptions"),completion);
                NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:25];
                while(!finished && deadline.timeIntervalSinceNow>0) [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:.1]];
            }
            NSData *data=[NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:&error];
            fwrite(data.bytes,1,data.length,stdout); fputc('\n',stdout);
            return [result[@"valid"] boolValue] && [result[@"addable"] boolValue] ? 0 : 4;
        }
        result[@"configuration"]=msg(face,@"JSONObjectRepresentation") ?: @{};
        result[@"directory"]=[msg(face,@"resourceDirectory") description] ?: @"";
        result[@"gallery"]=[arg(bundle,@"galleryFacesForDevice:",device) description] ?: @"";
        id manifest=msg(NSClassFromString(@"NTKParmesanResourcesManifest"),@"new");
        Dl_info info;
        IMP impl=class_getMethodImplementation([manifest class],NSSelectorFromString(@"validateImageListItem:withError:"));
        dladdr((void *)impl,&info);
        fprintf(stderr,"validateImageListItem offset: 0x%lx\n",(unsigned long)((uintptr_t)impl-(uintptr_t)info.dli_fbase));
        result[@"minVersion"]=@(((NSUInteger (*)(id,SEL))objc_msgSend)(manifest,NSSelectorFromString(@"minCompatibleVersion")));
        result[@"maxVersion"]=@(((NSUInteger (*)(id,SEL))objc_msgSend)(manifest,NSSelectorFromString(@"maxCompatibleVersion")));
        result[@"maxPhotos"]=@(((NSUInteger (*)(id,SEL))objc_msgSend)(manifest,NSSelectorFromString(@"maxNumberOfPhotos")));
        if (argc>1) {
            id asset=((id (*)(id,SEL,id,id))objc_msgSend)(NSClassFromString(@"NTKParmesanAsset"),
                NSSelectorFromString(@"tinkerAssetFromImageURL:withIdentifier:"),
                [NSURL fileURLWithPath:@(argv[1])],@"research-photo");
            ((void (*)(id,SEL,id))objc_msgSend)(asset,NSSelectorFromString(@"setModificationDate:"),[NSDate date]);
            ((void (*)(id,SEL,CGSize))objc_msgSend)(asset,NSSelectorFromString(@"setPresentationSize:"),CGSizeMake(410,502));
            result[@"asset"]=msg(asset,@"asDictionary") ?: @{};
            result[@"assetDirectory"]=[msg(asset,@"resourceDirectory") description] ?: @"";
            result[@"layouts"]=[msg(NSClassFromString(@"NTKParmesanTimeLayout"),@"allLayouts") description] ?: @"";
            if (argc>2) {
                NSString *directory=@(argv[2]);
                [[NSFileManager defaultManager] createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil];
                NSString *name=[@(argv[1]) lastPathComponent];
                NSString *destination=[directory stringByAppendingPathComponent:name];
                [[NSFileManager defaultManager] removeItemAtPath:destination error:nil];
                [[NSFileManager defaultManager] copyItemAtPath:@(argv[1]) toPath:destination error:nil];
                NSDictionary *raw=@{@"version":result[@"minVersion"],@"imageList":@[result[@"asset"]]};
                [raw writeToFile:[directory stringByAppendingPathComponent:@"Images.plist"] atomically:YES];
                id loaded=arg(NSClassFromString(@"NTKParmesanResourcesManifest"),@"manifestForResourceDirectory:",directory);
                NSError *validation=nil;
                BOOL valid=((BOOL (*)(id,SEL,NSError **))objc_msgSend)(loaded,NSSelectorFromString(@"validateManifestWithError:"),&validation);
                result[@"valid"]=@(valid);
                result[@"validationError"]=validation.description ?: @"";
                id reader=arg(NSClassFromString(@"NTKParmesanAssetReader"),@"readerForResourceDirectory:",directory);
                result[@"reader"]=[reader description] ?: @"";
                result[@"readAsset"]=msg(msg(reader,@"firstObject"),@"asDictionary") ?: @{};
                ((void (*)(id,SEL,id))objc_msgSend)(face,NSSelectorFromString(@"setResourceDirectory:"),directory);
                result[@"resourceConfiguration"]=msg(face,@"JSONObjectRepresentation") ?: @{};
                NSDictionary *slots=msg(face,@"_complicationSlotDescriptors");
                NSMutableDictionary *families=[NSMutableDictionary dictionary];
                for (NSString *slot in slots) families[slot]=msg(slots[slot],@"familiesRankedList") ?: @[];
                result[@"slotFamilies"]=families;
                NSMutableDictionary *options=[NSMutableDictionary dictionary];
                for (NSNumber *n in msg(face,@"customEditModes")) {
                    NSUInteger mode=n.unsignedIntegerValue;
                    id modeSlots=((id (*)(id,SEL,NSUInteger))objc_msgSend)(face,NSSelectorFromString(@"slotsForCustomEditMode:"),mode);
                    if ([modeSlots count]) {
                        NSMutableDictionary *slotted=[NSMutableDictionary dictionary];
                        for (id slot in modeSlots) {
                            id original=((id (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectedOptionForCustomEditMode:slot:"),mode,slot);
                            NSUInteger count=((NSUInteger (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"numberOfOptionsForCustomEditMode:slot:"),mode,slot);
                            if (count>256) continue;
                            NSMutableArray *values=[NSMutableArray array];
                            for (NSUInteger i=0;i<count;i++) {
                                id option=((id (*)(id,SEL,NSUInteger,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"optionAtIndex:forCustomEditMode:slot:"),i,mode,slot);
                                ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),option,mode,slot);
                                [values addObject:@{@"label":msg(option,@"localizedName") ?: @"",@"configuration":msg(face,@"JSONObjectRepresentation") ?: @{}}];
                            }
                            slotted[[slot description]]=values;
                            ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),original,mode,slot);
                        }
                        options[n.stringValue]=slotted;
                        continue;
                    }
                    id original=((id (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectedOptionForCustomEditMode:slot:"),mode,nil);
                    NSUInteger count=((NSUInteger (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"numberOfOptionsForCustomEditMode:slot:"),mode,nil);
                    if (count>32) continue;
                    NSMutableArray *values=[NSMutableArray array];
                    for (NSUInteger i=0;i<count;i++) {
                        id option=((id (*)(id,SEL,NSUInteger,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"optionAtIndex:forCustomEditMode:slot:"),i,mode,nil);
                        ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),option,mode,nil);
                        [values addObject:@{@"label":msg(option,@"localizedName") ?: @"",@"configuration":msg(face,@"JSONObjectRepresentation") ?: @{}}];
                    }
                    options[n.stringValue]=values;
                    ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),original,mode,nil);
                }
                result[@"options"]=options;
                // Palettes depend on the selected style. Keep the full native
                // dictionary so callers can derive slot and customData changes.
                NSMutableDictionary *palettes=[NSMutableDictionary dictionary];
                NSUInteger styleMode=15,colorMode=10;
                id originalStyle=((id (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectedOptionForCustomEditMode:slot:"),styleMode,nil);
                NSUInteger styles=((NSUInteger (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"numberOfOptionsForCustomEditMode:slot:"),styleMode,nil);
                for (NSUInteger i=0;i<styles && i<32;i++) {
                    id style=((id (*)(id,SEL,NSUInteger,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"optionAtIndex:forCustomEditMode:slot:"),i,styleMode,nil);
                    ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),style,styleMode,nil);
                    NSDictionary *configuration=msg(face,@"JSONObjectRepresentation");
                    NSMutableDictionary *slots=[NSMutableDictionary dictionary];
                    for (id slot in ((id (*)(id,SEL,NSUInteger))objc_msgSend)(face,NSSelectorFromString(@"slotsForCustomEditMode:"),colorMode)) {
                        id original=((id (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectedOptionForCustomEditMode:slot:"),colorMode,slot);
                        NSUInteger count=((NSUInteger (*)(id,SEL,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"numberOfOptionsForCustomEditMode:slot:"),colorMode,slot);
                        if (count>256) continue;
                        NSMutableArray *values=[NSMutableArray array];
                        for (NSUInteger j=0;j<count;j++) {
                            id option=((id (*)(id,SEL,NSUInteger,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"optionAtIndex:forCustomEditMode:slot:"),j,colorMode,slot);
                            ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),option,colorMode,slot);
                            [values addObject:@{@"label":msg(option,@"localizedName") ?: @"",@"configuration":msg(face,@"JSONObjectRepresentation") ?: @{}}];
                        }
                        slots[[slot description]]=values;
                        ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),original,colorMode,slot);
                    }
                    palettes[configuration[@"customization"][@"style"]]=@{@"baseline":configuration,@"slots":slots};
                }
                ((void (*)(id,SEL,id,NSUInteger,id))objc_msgSend)(face,NSSelectorFromString(@"selectOption:forCustomEditMode:slot:"),originalStyle,styleMode,nil);
                result[@"stylePalettes"]=palettes;
            }
        }
        NSError *error=nil;
        NSData *data=[NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted error:&error];
        if (!data) { fprintf(stderr,"%s\n",error.description.UTF8String); return 2; }
        fwrite(data.bytes,1,data.length,stdout); fputc('\n',stdout);
    } @catch(NSException *e) { fprintf(stderr,"%s\n",e.description.UTF8String); return 3; } }
}
