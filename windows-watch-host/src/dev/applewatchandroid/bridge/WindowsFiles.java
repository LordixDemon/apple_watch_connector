package dev.applewatchandroid.bridge;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

final class WindowsFiles {
    static void restrict(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Refusing linked state path");
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("Windows state requires an ACL-capable filesystem");
        UserPrincipal owner = view.getOwner();
        Set<AclEntryFlag> flags = Files.isDirectory(path) ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) : EnumSet.noneOf(AclEntryFlag.class);
        AclEntry access = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build();
        view.setAcl(List.of(access));
    }
}
