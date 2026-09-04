/* Auto-generated OpenCL dlopen shim for Android.
 * Forwards the CL functions ggml-opencl uses to the vendor driver,
 * loaded at runtime (apps cannot DT_NEEDED libOpenCL.so directly). */
#include <CL/cl.h>
#include <dlfcn.h>
#include <stddef.h>

static void *g_cl_lib = NULL;
static cl_int (CL_API_CALL *p_clGetPlatformIDs)(cl_uint, cl_platform_id *, cl_uint *);
static cl_int (CL_API_CALL *p_clGetPlatformInfo)(cl_platform_id, cl_platform_info, size_t, void *, size_t *);
static cl_int (CL_API_CALL *p_clGetDeviceIDs)(cl_platform_id, cl_device_type, cl_uint, cl_device_id *, cl_uint *);
static cl_int (CL_API_CALL *p_clGetDeviceInfo)(cl_device_id, cl_device_info, size_t, void *, size_t *);
static cl_context (CL_API_CALL *p_clCreateContext)(const cl_context_properties *, cl_uint, const cl_device_id *, void (CL_CALLBACK * p3)(const char *, const void *, size_t, void *), void *, cl_int *);
static cl_mem (CL_API_CALL *p_clCreateBuffer)(cl_context, cl_mem_flags, size_t, void *, cl_int *);
static cl_mem (CL_API_CALL *p_clCreateSubBuffer)(cl_mem, cl_mem_flags, cl_buffer_create_type, const void *, cl_int *);
static cl_mem (CL_API_CALL *p_clCreateImage)(cl_context, cl_mem_flags, const cl_image_format *, const cl_image_desc *, void *, cl_int *);
static cl_mem (CL_API_CALL *p_clCreateBufferWithProperties)(cl_context, const cl_mem_properties *, cl_mem_flags, size_t, void *, cl_int *);
static cl_int (CL_API_CALL *p_clReleaseMemObject)(cl_mem);
static cl_program (CL_API_CALL *p_clCreateProgramWithSource)(cl_context, cl_uint, const char **, const size_t *, cl_int *);
static cl_program (CL_API_CALL *p_clCreateProgramWithBinary)(cl_context, cl_uint, const cl_device_id *, const size_t *, const unsigned char **, cl_int *, cl_int *);
static cl_int (CL_API_CALL *p_clReleaseProgram)(cl_program);
static cl_int (CL_API_CALL *p_clBuildProgram)(cl_program, cl_uint, const cl_device_id *, const char *, void (CL_CALLBACK * p4)(cl_program, void *), void *);
static cl_int (CL_API_CALL *p_clGetProgramInfo)(cl_program, cl_program_info, size_t, void *, size_t *);
static cl_int (CL_API_CALL *p_clGetProgramBuildInfo)(cl_program, cl_device_id, cl_program_build_info, size_t, void *, size_t *);
static cl_kernel (CL_API_CALL *p_clCreateKernel)(cl_program, const char *, cl_int *);
static cl_int (CL_API_CALL *p_clReleaseKernel)(cl_kernel);
static cl_int (CL_API_CALL *p_clSetKernelArg)(cl_kernel, cl_uint, size_t, const void *);
static cl_int (CL_API_CALL *p_clGetKernelInfo)(cl_kernel, cl_kernel_info, size_t, void *, size_t *);
static cl_int (CL_API_CALL *p_clGetKernelWorkGroupInfo)(cl_kernel, cl_device_id, cl_kernel_work_group_info, size_t, void *, size_t *);
static cl_int (CL_API_CALL *p_clGetKernelSubGroupInfo)(cl_kernel, cl_device_id, cl_kernel_sub_group_info, size_t, const void*, size_t, void*, size_t*);
static cl_int (CL_API_CALL *p_clWaitForEvents)(cl_uint, const cl_event *);
static cl_int (CL_API_CALL *p_clReleaseEvent)(cl_event);
static cl_int (CL_API_CALL *p_clGetEventProfilingInfo)(cl_event, cl_profiling_info, size_t, void *, size_t *);
static cl_int (CL_API_CALL *p_clFlush)(cl_command_queue);
static cl_int (CL_API_CALL *p_clFinish)(cl_command_queue);
static cl_int (CL_API_CALL *p_clEnqueueReadBuffer)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, void *, cl_uint, const cl_event *, cl_event *);
static cl_int (CL_API_CALL *p_clEnqueueWriteBuffer)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, const void *, cl_uint, const cl_event *, cl_event *);
static cl_int (CL_API_CALL *p_clEnqueueFillBuffer)(cl_command_queue, cl_mem, const void *, size_t, size_t, size_t, cl_uint, const cl_event *, cl_event *);
static cl_int (CL_API_CALL *p_clEnqueueCopyBuffer)(cl_command_queue, cl_mem, cl_mem, size_t, size_t, size_t, cl_uint, const cl_event *, cl_event *);
static cl_int (CL_API_CALL *p_clEnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t *, const size_t *, const size_t *, cl_uint, const cl_event *, cl_event *);
static cl_int (CL_API_CALL *p_clEnqueueMarkerWithWaitList)(cl_command_queue, cl_uint, const cl_event *, cl_event *);
static cl_int (CL_API_CALL *p_clEnqueueBarrierWithWaitList)(cl_command_queue, cl_uint, const cl_event *, cl_event *);
static CL_API_PREFIX__VERSION_1_2_DEPRECATED cl_command_queue (CL_API_CALL *p_clCreateCommandQueue)(cl_context, cl_device_id, cl_command_queue_properties, cl_int *);

__attribute__((constructor)) static void opencl_shim_init(void) {
    static const char *cands[] = {
        "libOpenCL.so", "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so",
        "libOpenCL.so.1", NULL };
    for (int i = 0; cands[i]; i++) {
        void *h = dlopen(cands[i], RTLD_NOW | RTLD_LOCAL);
        if (!h) continue;
        int ok = 1;
        p_clGetPlatformIDs = dlsym(h, "clGetPlatformIDs"); if (!p_clGetPlatformIDs) ok = 0;
        p_clGetPlatformInfo = dlsym(h, "clGetPlatformInfo"); if (!p_clGetPlatformInfo) ok = 0;
        p_clGetDeviceIDs = dlsym(h, "clGetDeviceIDs"); if (!p_clGetDeviceIDs) ok = 0;
        p_clGetDeviceInfo = dlsym(h, "clGetDeviceInfo"); if (!p_clGetDeviceInfo) ok = 0;
        p_clCreateContext = dlsym(h, "clCreateContext"); if (!p_clCreateContext) ok = 0;
        p_clCreateBuffer = dlsym(h, "clCreateBuffer"); if (!p_clCreateBuffer) ok = 0;
        p_clCreateSubBuffer = dlsym(h, "clCreateSubBuffer"); if (!p_clCreateSubBuffer) ok = 0;
        p_clCreateImage = dlsym(h, "clCreateImage"); if (!p_clCreateImage) ok = 0;
        p_clCreateBufferWithProperties = dlsym(h, "clCreateBufferWithProperties"); if (!p_clCreateBufferWithProperties) ok = 0;
        p_clReleaseMemObject = dlsym(h, "clReleaseMemObject"); if (!p_clReleaseMemObject) ok = 0;
        p_clCreateProgramWithSource = dlsym(h, "clCreateProgramWithSource"); if (!p_clCreateProgramWithSource) ok = 0;
        p_clCreateProgramWithBinary = dlsym(h, "clCreateProgramWithBinary"); if (!p_clCreateProgramWithBinary) ok = 0;
        p_clReleaseProgram = dlsym(h, "clReleaseProgram"); if (!p_clReleaseProgram) ok = 0;
        p_clBuildProgram = dlsym(h, "clBuildProgram"); if (!p_clBuildProgram) ok = 0;
        p_clGetProgramInfo = dlsym(h, "clGetProgramInfo"); if (!p_clGetProgramInfo) ok = 0;
        p_clGetProgramBuildInfo = dlsym(h, "clGetProgramBuildInfo"); if (!p_clGetProgramBuildInfo) ok = 0;
        p_clCreateKernel = dlsym(h, "clCreateKernel"); if (!p_clCreateKernel) ok = 0;
        p_clReleaseKernel = dlsym(h, "clReleaseKernel"); if (!p_clReleaseKernel) ok = 0;
        p_clSetKernelArg = dlsym(h, "clSetKernelArg"); if (!p_clSetKernelArg) ok = 0;
        p_clGetKernelInfo = dlsym(h, "clGetKernelInfo"); if (!p_clGetKernelInfo) ok = 0;
        p_clGetKernelWorkGroupInfo = dlsym(h, "clGetKernelWorkGroupInfo"); if (!p_clGetKernelWorkGroupInfo) ok = 0;
        p_clGetKernelSubGroupInfo = dlsym(h, "clGetKernelSubGroupInfo"); if (!p_clGetKernelSubGroupInfo) ok = 0;
        p_clWaitForEvents = dlsym(h, "clWaitForEvents"); if (!p_clWaitForEvents) ok = 0;
        p_clReleaseEvent = dlsym(h, "clReleaseEvent"); if (!p_clReleaseEvent) ok = 0;
        p_clGetEventProfilingInfo = dlsym(h, "clGetEventProfilingInfo"); if (!p_clGetEventProfilingInfo) ok = 0;
        p_clFlush = dlsym(h, "clFlush"); if (!p_clFlush) ok = 0;
        p_clFinish = dlsym(h, "clFinish"); if (!p_clFinish) ok = 0;
        p_clEnqueueReadBuffer = dlsym(h, "clEnqueueReadBuffer"); if (!p_clEnqueueReadBuffer) ok = 0;
        p_clEnqueueWriteBuffer = dlsym(h, "clEnqueueWriteBuffer"); if (!p_clEnqueueWriteBuffer) ok = 0;
        p_clEnqueueFillBuffer = dlsym(h, "clEnqueueFillBuffer"); if (!p_clEnqueueFillBuffer) ok = 0;
        p_clEnqueueCopyBuffer = dlsym(h, "clEnqueueCopyBuffer"); if (!p_clEnqueueCopyBuffer) ok = 0;
        p_clEnqueueNDRangeKernel = dlsym(h, "clEnqueueNDRangeKernel"); if (!p_clEnqueueNDRangeKernel) ok = 0;
        p_clEnqueueMarkerWithWaitList = dlsym(h, "clEnqueueMarkerWithWaitList"); if (!p_clEnqueueMarkerWithWaitList) ok = 0;
        p_clEnqueueBarrierWithWaitList = dlsym(h, "clEnqueueBarrierWithWaitList"); if (!p_clEnqueueBarrierWithWaitList) ok = 0;
        p_clCreateCommandQueue = dlsym(h, "clCreateCommandQueue"); if (!p_clCreateCommandQueue) ok = 0;
        if (ok) { g_cl_lib = h; break; }
        g_cl_lib = NULL;
    }
}

int opencl_shim_available(void) { return g_cl_lib != NULL; }
CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(cl_uint p0, cl_platform_id * p1, cl_uint * p2) {
    if (!g_cl_lib || !p_clGetPlatformIDs) return -1001;
    return p_clGetPlatformIDs(p0, p1, p2);
}

CL_API_ENTRY cl_int CL_API_CALL clGetPlatformInfo(cl_platform_id p0, cl_platform_info p1, size_t p2, void * p3, size_t * p4) {
    if (!p_clGetPlatformInfo) return (cl_int)0;
    return p_clGetPlatformInfo(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_int CL_API_CALL clGetDeviceIDs(cl_platform_id p0, cl_device_type p1, cl_uint p2, cl_device_id * p3, cl_uint * p4) {
    if (!p_clGetDeviceIDs) return (cl_int)0;
    return p_clGetDeviceIDs(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_int CL_API_CALL clGetDeviceInfo(cl_device_id p0, cl_device_info p1, size_t p2, void * p3, size_t * p4) {
    if (!p_clGetDeviceInfo) return (cl_int)0;
    return p_clGetDeviceInfo(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_context CL_API_CALL clCreateContext(const cl_context_properties * p0, cl_uint p1, const cl_device_id * p2, void (CL_CALLBACK * p3)(const char *, const void *, size_t, void *), void * p4, cl_int * p5) {
    if (!p_clCreateContext) return (cl_context)0;
    return p_clCreateContext(p0, p1, p2, p3, p4, p5);
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateBuffer(cl_context p0, cl_mem_flags p1, size_t p2, void * p3, cl_int * p4) {
    if (!p_clCreateBuffer) return (cl_mem)0;
    return p_clCreateBuffer(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateSubBuffer(cl_mem p0, cl_mem_flags p1, cl_buffer_create_type p2, const void * p3, cl_int * p4) {
    if (!p_clCreateSubBuffer) return (cl_mem)0;
    return p_clCreateSubBuffer(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateImage(cl_context p0, cl_mem_flags p1, const cl_image_format * p2, const cl_image_desc * p3, void * p4, cl_int * p5) {
    if (!p_clCreateImage) return (cl_mem)0;
    return p_clCreateImage(p0, p1, p2, p3, p4, p5);
}

CL_API_ENTRY cl_mem CL_API_CALL clCreateBufferWithProperties(cl_context p0, const cl_mem_properties * p1, cl_mem_flags p2, size_t p3, void * p4, cl_int * p5) {
    if (!p_clCreateBufferWithProperties) return (cl_mem)0;
    return p_clCreateBufferWithProperties(p0, p1, p2, p3, p4, p5);
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseMemObject(cl_mem p0) {
    if (!p_clReleaseMemObject) return (cl_int)0;
    return p_clReleaseMemObject(p0);
}

CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithSource(cl_context p0, cl_uint p1, const char ** p2, const size_t * p3, cl_int * p4) {
    if (!p_clCreateProgramWithSource) return (cl_program)0;
    return p_clCreateProgramWithSource(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithBinary(cl_context p0, cl_uint p1, const cl_device_id * p2, const size_t * p3, const unsigned char ** p4, cl_int * p5, cl_int * p6) {
    if (!p_clCreateProgramWithBinary) return (cl_program)0;
    return p_clCreateProgramWithBinary(p0, p1, p2, p3, p4, p5, p6);
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseProgram(cl_program p0) {
    if (!p_clReleaseProgram) return (cl_int)0;
    return p_clReleaseProgram(p0);
}

CL_API_ENTRY cl_int CL_API_CALL clBuildProgram(cl_program p0, cl_uint p1, const cl_device_id * p2, const char * p3, void (CL_CALLBACK * p4)(cl_program, void *), void * p5) {
    if (!p_clBuildProgram) return (cl_int)0;
    return p_clBuildProgram(p0, p1, p2, p3, p4, p5);
}

CL_API_ENTRY cl_int CL_API_CALL clGetProgramInfo(cl_program p0, cl_program_info p1, size_t p2, void * p3, size_t * p4) {
    if (!p_clGetProgramInfo) return (cl_int)0;
    return p_clGetProgramInfo(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_int CL_API_CALL clGetProgramBuildInfo(cl_program p0, cl_device_id p1, cl_program_build_info p2, size_t p3, void * p4, size_t * p5) {
    if (!p_clGetProgramBuildInfo) return (cl_int)0;
    return p_clGetProgramBuildInfo(p0, p1, p2, p3, p4, p5);
}

CL_API_ENTRY cl_kernel CL_API_CALL clCreateKernel(cl_program p0, const char * p1, cl_int * p2) {
    if (!p_clCreateKernel) return (cl_kernel)0;
    return p_clCreateKernel(p0, p1, p2);
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseKernel(cl_kernel p0) {
    if (!p_clReleaseKernel) return (cl_int)0;
    return p_clReleaseKernel(p0);
}

CL_API_ENTRY cl_int CL_API_CALL clSetKernelArg(cl_kernel p0, cl_uint p1, size_t p2, const void * p3) {
    if (!p_clSetKernelArg) return (cl_int)0;
    return p_clSetKernelArg(p0, p1, p2, p3);
}

CL_API_ENTRY cl_int CL_API_CALL clGetKernelInfo(cl_kernel p0, cl_kernel_info p1, size_t p2, void * p3, size_t * p4) {
    if (!p_clGetKernelInfo) return (cl_int)0;
    return p_clGetKernelInfo(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_int CL_API_CALL clGetKernelWorkGroupInfo(cl_kernel p0, cl_device_id p1, cl_kernel_work_group_info p2, size_t p3, void * p4, size_t * p5) {
    if (!p_clGetKernelWorkGroupInfo) return (cl_int)0;
    return p_clGetKernelWorkGroupInfo(p0, p1, p2, p3, p4, p5);
}

CL_API_ENTRY cl_int CL_API_CALL clGetKernelSubGroupInfo(cl_kernel p0, cl_device_id p1, cl_kernel_sub_group_info p2, size_t p3, const void* p4, size_t p5, void* p6, size_t* p7) {
    if (!p_clGetKernelSubGroupInfo) return (cl_int)0;
    return p_clGetKernelSubGroupInfo(p0, p1, p2, p3, p4, p5, p6, p7);
}

CL_API_ENTRY cl_int CL_API_CALL clWaitForEvents(cl_uint p0, const cl_event * p1) {
    if (!p_clWaitForEvents) return (cl_int)0;
    return p_clWaitForEvents(p0, p1);
}

CL_API_ENTRY cl_int CL_API_CALL clReleaseEvent(cl_event p0) {
    if (!p_clReleaseEvent) return (cl_int)0;
    return p_clReleaseEvent(p0);
}

CL_API_ENTRY cl_int CL_API_CALL clGetEventProfilingInfo(cl_event p0, cl_profiling_info p1, size_t p2, void * p3, size_t * p4) {
    if (!p_clGetEventProfilingInfo) return (cl_int)0;
    return p_clGetEventProfilingInfo(p0, p1, p2, p3, p4);
}

CL_API_ENTRY cl_int CL_API_CALL clFlush(cl_command_queue p0) {
    if (!p_clFlush) return (cl_int)0;
    return p_clFlush(p0);
}

CL_API_ENTRY cl_int CL_API_CALL clFinish(cl_command_queue p0) {
    if (!p_clFinish) return (cl_int)0;
    return p_clFinish(p0);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueReadBuffer(cl_command_queue p0, cl_mem p1, cl_bool p2, size_t p3, size_t p4, void * p5, cl_uint p6, const cl_event * p7, cl_event * p8) {
    if (!p_clEnqueueReadBuffer) return (cl_int)0;
    return p_clEnqueueReadBuffer(p0, p1, p2, p3, p4, p5, p6, p7, p8);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueWriteBuffer(cl_command_queue p0, cl_mem p1, cl_bool p2, size_t p3, size_t p4, const void * p5, cl_uint p6, const cl_event * p7, cl_event * p8) {
    if (!p_clEnqueueWriteBuffer) return (cl_int)0;
    return p_clEnqueueWriteBuffer(p0, p1, p2, p3, p4, p5, p6, p7, p8);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueFillBuffer(cl_command_queue p0, cl_mem p1, const void * p2, size_t p3, size_t p4, size_t p5, cl_uint p6, const cl_event * p7, cl_event * p8) {
    if (!p_clEnqueueFillBuffer) return (cl_int)0;
    return p_clEnqueueFillBuffer(p0, p1, p2, p3, p4, p5, p6, p7, p8);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueCopyBuffer(cl_command_queue p0, cl_mem p1, cl_mem p2, size_t p3, size_t p4, size_t p5, cl_uint p6, const cl_event * p7, cl_event * p8) {
    if (!p_clEnqueueCopyBuffer) return (cl_int)0;
    return p_clEnqueueCopyBuffer(p0, p1, p2, p3, p4, p5, p6, p7, p8);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueNDRangeKernel(cl_command_queue p0, cl_kernel p1, cl_uint p2, const size_t * p3, const size_t * p4, const size_t * p5, cl_uint p6, const cl_event * p7, cl_event * p8) {
    if (!p_clEnqueueNDRangeKernel) return (cl_int)0;
    return p_clEnqueueNDRangeKernel(p0, p1, p2, p3, p4, p5, p6, p7, p8);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueMarkerWithWaitList(cl_command_queue p0, cl_uint p1, const cl_event * p2, cl_event * p3) {
    if (!p_clEnqueueMarkerWithWaitList) return (cl_int)0;
    return p_clEnqueueMarkerWithWaitList(p0, p1, p2, p3);
}

CL_API_ENTRY cl_int CL_API_CALL clEnqueueBarrierWithWaitList(cl_command_queue p0, cl_uint p1, const cl_event * p2, cl_event * p3) {
    if (!p_clEnqueueBarrierWithWaitList) return (cl_int)0;
    return p_clEnqueueBarrierWithWaitList(p0, p1, p2, p3);
}

CL_API_ENTRY CL_API_PREFIX__VERSION_1_2_DEPRECATED cl_command_queue CL_API_CALL clCreateCommandQueue(cl_context p0, cl_device_id p1, cl_command_queue_properties p2, cl_int * p3) {
    if (!p_clCreateCommandQueue) return (CL_API_PREFIX__VERSION_1_2_DEPRECATED cl_command_queue)0;
    return p_clCreateCommandQueue(p0, p1, p2, p3);
}

