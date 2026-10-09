#import <Foundation/Foundation.h>
#import <dlfcn.h>

// Constructs local options only. No browser, listener, connection or Watch state.
int main(void) {
    @autoreleasepool {
        void *network = dlopen("/System/Library/Frameworks/Network.framework/Network", RTLD_NOW);
        if (!network) return 2;
        id (*parameters)(void) = dlsym(network, "nw_parameters_create_application_service_quic");
        id (*stack)(id) = dlsym(network, "nw_parameters_copy_default_protocol_stack");
        void (*iterate)(id, void (^)(id)) = dlsym(network, "nw_protocol_stack_iterate_application_protocols");
        id (*key)(id) = dlsym(network, "nw_quic_options_copy_local_public_key");
        if (!parameters || !stack || !iterate || !key) return 3;
        id configuration = parameters();
        if (!configuration) return 4;
        __block BOOL found = NO;
        iterate(stack(configuration), ^(id options) {
            id value = key(options);
            if (![value isKindOfClass:NSData.class]) return;
            NSData *data = value;
            const unsigned char *bytes = data.bytes;
            // Only a freshly generated local public key's shape, never a secret.
            fprintf(stderr, "Local QUIC public key: length=%lu prefix=", (unsigned long)data.length);
            for (NSUInteger i = 0; i < MIN(data.length, 12); i++) fprintf(stderr, "%02x", bytes[i]);
            fprintf(stderr, "\n");
            found = YES;
        });
        return found ? 0 : 5;
    }
}
