using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

// SetupAPI selects the inbox Microsoft driver for one explicitly chosen node.
// No generated INF, publisher certificate, or driver-signing override is used.
public static class WatchControllerDriver {
    [StructLayout(LayoutKind.Sequential)] public struct DeviceInfo {
        public uint Size; public Guid ClassGuid; public uint DevInst; public UIntPtr Reserved;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct InstallParams {
        public uint Size, Flags, FlagsEx; public IntPtr Window, Handler, Context, FileQueue;
        public UIntPtr ClassReserved; public uint Reserved;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=260)] public string DriverPath;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct DriverInfo {
        public uint Size, Type; public UIntPtr Reserved;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=256)] public string Description;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=256)] public string Manufacturer;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=256)] public string Provider;
        public System.Runtime.InteropServices.ComTypes.FILETIME Date; public ulong Version;
    }
    [DllImport("setupapi.dll", SetLastError=true)] static extern IntPtr SetupDiCreateDeviceInfoList(IntPtr classGuid, IntPtr window);
    [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiOpenDeviceInfo(IntPtr set, string id, IntPtr window, uint flags, ref DeviceInfo device);
    [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiGetDeviceInstallParams(IntPtr set, IntPtr device, ref InstallParams p);
    [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiSetDeviceInstallParams(IntPtr set, IntPtr device, ref InstallParams p);
    [DllImport("setupapi.dll", SetLastError=true)] static extern bool SetupDiBuildDriverInfoList(IntPtr set, IntPtr device, uint type);
    [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiEnumDriverInfo(IntPtr set, IntPtr device, uint type, uint index, ref DriverInfo driver);
    [DllImport("setupapi.dll")] static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
    [DllImport("newdev.dll", SetLastError=true)] static extern bool DiInstallDevice(IntPtr window, IntPtr set, ref DeviceInfo device, ref DriverInfo driver, uint flags, out bool reboot);
    static void Check(bool ok, string action) { if(!ok) {int code=Marshal.GetLastWin32Error();throw new Win32Exception(code,action+" error 0x"+code.ToString("X8"));} }
    public static string Run(string id, string inf, string description, bool install, bool compatible=false) {
        IntPtr set=SetupDiCreateDeviceInfoList(IntPtr.Zero,IntPtr.Zero);
        if(set==new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error());
        IntPtr pointer=IntPtr.Zero;
        try {
            DeviceInfo device=new DeviceInfo {Size=(uint)Marshal.SizeOf(typeof(DeviceInfo))};
            Check(SetupDiOpenDeviceInfo(set,id,IntPtr.Zero,0,ref device),"Open exact device");
            if(compatible) {pointer=Marshal.AllocHGlobal(Marshal.SizeOf(typeof(DeviceInfo))); Marshal.StructureToPtr(device,pointer,false);}
            InstallParams p=new InstallParams {Size=(uint)Marshal.SizeOf(typeof(InstallParams))};
            Check(SetupDiGetDeviceInstallParams(set,pointer,ref p),"Get driver parameters");
            p.Flags |= 0x10000; p.FlagsEx |= 0x800; p.DriverPath=inf;
            Check(SetupDiSetDeviceInstallParams(set,pointer,ref p),"Set single INF");
            uint type=compatible ? 2u : 1u;
            Check(SetupDiBuildDriverInfoList(set,pointer,type),"Build drivers");
            if(!compatible) {
                Check(SetupDiOpenDeviceInfo(set,id,IntPtr.Zero,2,ref device),"Inherit the checked class driver list");
                pointer=Marshal.AllocHGlobal(Marshal.SizeOf(typeof(DeviceInfo))); Marshal.StructureToPtr(device,pointer,false);
            }
            device=(DeviceInfo)Marshal.PtrToStructure(pointer,typeof(DeviceInfo));
            string descriptions=""; DriverInfo found=new DriverInfo(); int matches=0;
            for(uint i=0;;i++) {
                DriverInfo driver=new DriverInfo {Size=(uint)Marshal.SizeOf(typeof(DriverInfo))};
                if(!SetupDiEnumDriverInfo(set,pointer,type,i,ref driver)) {
                    if(Marshal.GetLastWin32Error()!=259) throw new Win32Exception(Marshal.GetLastWin32Error(),"Enumerate drivers");
                    break;
                }
                descriptions+=driver.Description+" | "+driver.Provider+"\n";
                if(driver.Description==description) {found=driver;matches++;}
            }
            if(!install) return descriptions;
            if(matches!=1) throw new InvalidOperationException("Driver match must be unique: "+descriptions);
            InstallParams deviceParams=new InstallParams {Size=(uint)Marshal.SizeOf(typeof(InstallParams))};
            Check(SetupDiGetDeviceInstallParams(set,pointer,ref deviceParams),"Get device installation parameters");
            deviceParams.FlagsEx|=0x20000000; // restart only the selected device
            deviceParams.Flags|=0x00800000; // no extra installation wizard
            Check(SetupDiSetDeviceInstallParams(set,pointer,ref deviceParams),"Set device installation parameters");
            bool reboot;
            Check(DiInstallDevice(IntPtr.Zero,set,ref device,ref found,0,out reboot),"Install selected driver");
            return "Installed "+found.Description+"; reboot="+reboot;
        } finally {if(pointer!=IntPtr.Zero) Marshal.FreeHGlobal(pointer);SetupDiDestroyDeviceInfoList(set);}
    }
}
