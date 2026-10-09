#import <Foundation/Foundation.h>
#import <dlfcn.h>

// Data-only CoreUtils codec probe. No IDS, pairing, publishers or Watch library.
int main(int argc, const char **argv) {
    @autoreleasepool {
        BOOL boundary = argc == 3 && strcmp(argv[1], "--boundary-strings") == 0;
        if (argc != 2 && !boundary) return 2;
        NSString *path = [[NSString stringWithUTF8String:argv[boundary ? 2 : 1]] stringByStandardizingPath];
        if ((![path hasPrefix:@"/tmp/"] && ![path hasPrefix:@"/private/tmp/"])
                || [[NSFileManager defaultManager] fileExistsAtPath:path]) return 3;
        void *core = dlopen("/System/Library/PrivateFrameworks/CoreUtils.framework/CoreUtils", RTLD_NOW);
        if (!core) return 4;
        CFDataRef (*encode)(CFTypeRef, uint32_t, int *) = dlsym(core, "OPACKEncoderCreateData");
        CFTypeRef (*decode)(CFDataRef, uint32_t, int *) = dlsym(core, "OPACKDecodeData");
        if (!encode || !decode) return 5;
        NSData *bytes = [NSData dataWithBytes:(uint8_t[]){1, 2, 3, 4} length:4];
        NSArray *input = boundary ? @[
            [@"x" stringByPaddingToLength:65535 withString:@"x" startingAtIndex:0],
            [@"x" stringByPaddingToLength:65536 withString:@"x" startingAtIndex:0],
            @"Превью 🌍", @"Превью 🌍"] : @[@YES, @NO, [NSNull null], @0, @39, @40, @300,
            @70000, @1099511627776LL, @-1, @((float)1.5), @-12.25,
            [NSDate dateWithTimeIntervalSinceReferenceDate:750000000.25],
            [[NSUUID alloc] initWithUUIDString:@"00112233-4455-6677-8899-aabbccddeeff"],
            @"", @"a", @"a", @"native snapshot", @"native snapshot", bytes, bytes,
            [@"x" stringByPaddingToLength:300 withString:@"x" startingAtIndex:0],
            @[@"nested", @"native snapshot"],
            @{@"id": @"native snapshot", @"flags": @YES},
            [NSData data], @"after empty", @"after empty"];
        int error = 0;
        NSData *encoded = CFBridgingRelease(encode((__bridge CFTypeRef)input, 0, &error));
        if (error || !encoded.length || encoded.length > 262144
                || ![encoded writeToFile:path atomically:NO]) return 6;
        // A decoder must track each inline object even when equal to an earlier
        // value. Verify this with the native decoder, independently of encoding.
        NSData *duplicate = [NSData dataWithBytes:(uint8_t[]){0xd3, 0x41, 'a', 0x41, 'a', 0xa1} length:6];
        id checked = CFBridgingRelease(decode((__bridge CFDataRef)duplicate, 0, &error));
        if (error || ![checked isEqual:@[@"a", @"a", @"a"]]) return 7;
        for (NSData *reference in @[
                [NSData dataWithBytes:(uint8_t[]){0xd2, 0x41, 'a', 0xc3, 0, 0, 0, 0} length:8],
                [NSData dataWithBytes:(uint8_t[]){0xd2, 0x41, 'a', 0xc4, 0, 0, 0, 0, 0, 0, 0, 0} length:12]]) {
            id result = CFBridgingRelease(decode((__bridge CFDataRef)reference, 0, &error));
            if (error || ![result isEqual:@[@"a", @"a"]]) return 8;
        }
        fprintf(stderr, "Native OPACK scalar fixture: %lu bytes, %lu input items\n",
            (unsigned long)encoded.length, (unsigned long)input.count);
        return 0;
    }
}
