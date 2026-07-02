#import <XCTest/XCTest.h>
#import "XCAccessibilityElement.h"

@interface AXClientProxy : NSObject

+ (instancetype)sharedClient;

- (NSArray<id<XCAccessibilityElement>> *)activeApplications;

- (NSDictionary *)defaultParameters;

/// Raises the accessibility snapshot timeout via the private XCAXClient_iOS API, so a single
/// snapshot can wait through a blocked app main thread (e.g. a lazily-bundled heavy screen)
/// instead of failing at the framework default (~30s). Returns NO with an error when the
/// private API is unavailable on this XCTest version; callers must treat that as non-fatal.
- (BOOL)setAXTimeoutSeconds:(double)seconds error:(NSError **)error;

@end
