#import <Foundation/Foundation.h>
#import <Network/Network.h>
#import <Security/Security.h>
#import <dlfcn.h>

// Controlled local loopback only. Never discovers or connects to a Watch.
// The test listener accepts its disposable test client; production TLS pinning
// is independently enforced by the Rust engine and is not changed here.
int main(int argc, const char **argv) {
    @autoreleasepool {
        BOOL clientMode = argc == 3 && strcmp(argv[2], "--native-client") == 0;
        BOOL replaceMode = argc == 3 && strcmp(argv[2], "--replace") == 0;
        if (argc != 2 && !clientMode && !replaceMode) return 2;
        NSString *path = [NSString stringWithUTF8String:argv[1]];
        if (![path hasPrefix:@"/tmp/"] || [[NSFileManager defaultManager] fileExistsAtPath:path]) return 3;
        void *network = dlopen("/System/Library/Frameworks/Network.framework/Network", RTLD_NOW);
        nw_parameters_t (*parameters)(void) = dlsym(network, "nw_parameters_create_application_service_quic");
        NSData *(*key)(nw_protocol_options_t) = dlsym(network, "nw_quic_options_copy_local_public_key");
        sec_protocol_options_t (*security)(nw_protocol_options_t) = dlsym(network, "nw_quic_copy_sec_protocol_options");
        void (*attach)(nw_parameters_t, bool) = dlsym(network, "nw_parameters_set_attach_protocol_listener");
        if (!parameters || !key || !security || !attach) return 4;
        nw_parameters_t config = parameters();
        nw_protocol_stack_t stack = nw_parameters_copy_default_protocol_stack(config);
        __block BOOL found = NO;
        nw_protocol_stack_iterate_application_protocols(stack, ^(nw_protocol_options_t options) {
            NSData *publicKey = key(options);
            if (!publicKey.length) return;
            found = [publicKey writeToFile:path atomically:NO];
            sec_protocol_options_set_verify_block(security(options),
                ^(sec_protocol_metadata_t metadata, sec_trust_t trust, sec_protocol_verify_complete_t complete) {
                    complete(true);
                }, dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0));
        });
        if (!found) return 5;
        if (clientMode) {
            dispatch_queue_t queue = dispatch_queue_create("controlled.quic.client", DISPATCH_QUEUE_SERIAL);
            dispatch_semaphore_t done = dispatch_semaphore_create(0);
            nw_connection_t connection = nw_connection_create(nw_endpoint_create_host("127.0.0.1", "55333"), config);
            nw_connection_set_queue(connection, queue);
            nw_connection_set_state_changed_handler(connection, ^(nw_connection_state_t state, nw_error_t error) {
                fprintf(stderr, "Controlled native client state=%u\n", state);
                if (state == nw_connection_state_failed) dispatch_semaphore_signal(done);
                if (state != nw_connection_state_ready) return;
                nw_protocol_metadata_t metadata = nw_connection_copy_protocol_metadata(connection, nw_protocol_copy_quic_definition());
                fprintf(stderr, "Controlled native client stream id=%llu\n", metadata ? nw_quic_get_stream_id(metadata) : UINT64_MAX);
                const char prefix[20] = "controlled-prefix";
                dispatch_data_t data = dispatch_data_create(prefix, sizeof(prefix), queue, DISPATCH_DATA_DESTRUCTOR_DEFAULT);
                nw_connection_send(connection, data, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, true,
                    ^(nw_error_t sendError) { fprintf(stderr, "Controlled client send error=%d\n", sendError ? nw_error_get_error_code(sendError) : 0); });
                nw_connection_receive(connection, 15, 15, ^(dispatch_data_t content, nw_content_context_t context, bool complete, nw_error_t receiveError) {
                    fprintf(stderr, "Controlled client response bytes=%zu error=%d\n", content ? dispatch_data_get_size(content) : 0,
                        receiveError ? nw_error_get_error_code(receiveError) : 0);
                    dispatch_semaphore_signal(done);
                });
            });
            nw_connection_start(connection);
            dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC));
            nw_connection_cancel(connection);
            return 0;
        }
        attach(config, true);
        nw_parameters_set_local_endpoint(config, nw_endpoint_create_host("127.0.0.1", "55333"));
        nw_listener_t listener = nw_listener_create(config);
        if (!listener) return 6;
        dispatch_queue_t queue = dispatch_queue_create("controlled.quic.loopback", DISPATCH_QUEUE_SERIAL);
        nw_listener_set_queue(listener, queue);
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        nw_listener_set_state_changed_handler(listener, ^(nw_listener_state_t state, nw_error_t error) {
            fprintf(stderr, "Controlled listener state=%u\n", state);
            if (state == nw_listener_state_failed) dispatch_semaphore_signal(done);
        });
        __block nw_connection_t previous = nil;
        nw_listener_set_new_connection_handler(listener, ^(nw_connection_t connection) {
            fprintf(stderr, "Controlled native connection accepted\n");
            if (replaceMode && previous) nw_connection_cancel(previous);
            previous = connection;
            nw_connection_set_queue(connection, queue);
            nw_connection_set_state_changed_handler(connection, ^(nw_connection_state_t state, nw_error_t error) {
                fprintf(stderr, "Controlled connection state=%u\n", state);
                if (state != nw_connection_state_ready) return;
                nw_protocol_metadata_t metadata = nw_connection_copy_protocol_metadata(connection, nw_protocol_copy_quic_definition());
                fprintf(stderr, "Controlled native stream id=%llu\n", metadata ? nw_quic_get_stream_id(metadata) : UINT64_MAX);
                nw_connection_receive(connection, 20, 20,
                    ^(dispatch_data_t content, nw_content_context_t context, bool complete, nw_error_t error) {
                        size_t size = content ? dispatch_data_get_size(content) : 0;
                        fprintf(stderr, "Controlled native stream prefix received=%zu complete=%d error=%d\n",
                            size, complete, error ? nw_error_get_error_code(error) : 0);
                        if (size == 20) {
                            const char reply[] = "native-loopback";
                            dispatch_data_t data = dispatch_data_create(reply, sizeof(reply) - 1, queue, DISPATCH_DATA_DESTRUCTOR_DEFAULT);
                            nw_connection_send(connection, data, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, true,
                                ^(nw_error_t sendError) { fprintf(stderr, "Controlled reply error=%d\n", sendError ? nw_error_get_error_code(sendError) : 0); });
                        }
                    });
            });
            nw_connection_start(connection);
        });
        nw_listener_start(listener);
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 35 * NSEC_PER_SEC));
        nw_listener_cancel(listener);
        return 0;
    }
}
