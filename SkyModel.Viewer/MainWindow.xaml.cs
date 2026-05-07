using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Media3D;
using System.IO;
using SkyModel.Core.Indexing;
using SkyModel.Core.Models;
using SkyModel.Core.Parsing;
using SkyModel.Core.Resources;

namespace SkyModel.Viewer;

public partial class MainWindow : Window
{
    private sealed class MeshTreeFolderNode
    {
        public required string Name { get; init; }
        public Dictionary<string, MeshTreeFolderNode> Children { get; } = new(StringComparer.OrdinalIgnoreCase);
        public List<MeshCatalogEntry> Meshes { get; } = new();
    }

    private enum ViewMode
    {
        Texture,
        Solid,
        Wire,
    }

    private ScanResult? _scanResult;
    private MeshGeometry3D? _currentGeometry;
    private Point3DCollection? _currentPositions;
    private MaterialInfo? _currentMaterialInfo;
    private string? _currentMeshPath;
    private Model3D? _currentSkeleton;
    private readonly PerspectiveCamera _camera = new();
    private System.Windows.Point _lastMousePosition;
    private bool _isOrbiting;
    private bool _isPanning;
    private bool _isLightOrbiting;
    private Point3D _cameraTarget = new(0, 0, 0);
    private double _cameraYaw = -2.3561944902;
    private double _cameraPitch = -0.35;
    private double _cameraDistance = 5.0;
    private double _lightYaw = -2.0344439358;
    private double _lightPitch = -0.52;
    private DirectionalLight? _keyLight;
    private DirectionalLight? _fillLight;
    private ViewMode _viewMode = ViewMode.Texture;
    private List<MeshCatalogEntry> _allMeshes = new();

    public MainWindow()
    {
        Program.LogMessage("MainWindow constructor started");
        InitializeComponent();
        Program.LogMessage("InitializeComponent completed");
        StatusText.Text = "Ready";
        MeshCountText.Text = "No index loaded";
        SelectionTitle.Text = "No mesh selected";
        SelectionMeta.Text = string.Empty;
        DetailsBox.Text = "No install location indexed.";
        SetDefaultInstallLocation();
        MeshViewport.Camera = _camera;
        UpdateCamera();
        SceneVisual.Content = CreateEmptyScene();
        Program.LogMessage("MainWindow constructor completed");
    }

    private void SetDefaultInstallLocation()
    {
        var detected = SkyInstallScanner.FindDefaultInstallLocation();
        if (detected is null)
        {
            StatusText.Text = "Select install location";
            DetailsBox.Text = "Select the Sky install location containing Sky.exe and data/assets.";
            return;
        }

        InstallPathBox.Text = detected;
        StatusText.Text = "Install location detected";
        DetailsBox.Text = "Ready to index detected Sky install location.";
    }

    private async void ScanButton_Click(object sender, RoutedEventArgs e)
    {
        var installPath = InstallPathBox.Text.Trim().Trim('"');
        if (string.IsNullOrWhiteSpace(installPath))
        {
            MessageBox.Show("Please provide a Sky install location.", "Missing Path", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        if (!SkyInstallScanner.IsValidInstallLocation(installPath))
        {
            StatusText.Text = "Invalid install location";
            DetailsBox.Text = "Select the Sky install location containing Sky.exe and data/assets.";
            MessageBox.Show(
                "Select the Sky install location containing Sky.exe and data/assets.",
                "Invalid Install Location",
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
            return;
        }

        try
        {
            ScanButton.IsEnabled = false;
            BrowseInstallLocationButton.IsEnabled = false;
            StatusText.Text = "Indexing assets...";
            SelectionTitle.Text = "No mesh selected";
            SelectionMeta.Text = string.Empty;
            DetailsBox.Text = "Indexing install assets...";
            MeshTree.Items.Clear();

            _scanResult = await Task.Run(() => SkyInstallScanner.Scan(installPath));
            _allMeshes = _scanResult.Meshes.ToList();
            ApplyMeshFilter();
            StatusText.Text = $"Indexed {_allMeshes.Count:N0} meshes";
            DetailsBox.Text = $"Index complete\nInstall: {_scanResult.InstallRoot}\nAssets: {_scanResult.AssetsRoot}\nMeshes: {_scanResult.Meshes.Count:N0}\nIndexed Names: {_allMeshes.Count:N0}";
        }
        catch (Exception ex)
        {
            StatusText.Text = "Index failed";
            MeshCountText.Text = "Index failed";
            DetailsBox.Text = ex.ToString();
            MessageBox.Show(ex.Message, "Index Failed", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            ScanButton.IsEnabled = true;
            BrowseInstallLocationButton.IsEnabled = true;
        }
    }

    private void BrowseInstallLocationButton_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "Select the Sky install location",
            Multiselect = false,
        };

        var currentPath = InstallPathBox.Text.Trim().Trim('"');
        if (Directory.Exists(currentPath))
        {
            dialog.InitialDirectory = currentPath;
        }

        if (dialog.ShowDialog(this) != true)
        {
            return;
        }

        InstallPathBox.Text = dialog.FolderName;
        StatusText.Text = SkyInstallScanner.IsValidInstallLocation(dialog.FolderName)
            ? "Install location selected"
            : "Invalid install location";
    }

    private void PopulateTree(IEnumerable<MeshCatalogEntry> meshes)
    {
        MeshTree.Items.Clear();

        var root = new MeshTreeFolderNode { Name = string.Empty };
        foreach (var mesh in meshes)
        {
            var parts = mesh.RelativePath.Split('/');
            if (parts.Length == 0)
            {
                continue;
            }

            var folderParts = parts.Take(parts.Length - 1);
            var node = root;
            foreach (var folder in folderParts)
            {
                if (!node.Children.TryGetValue(folder, out var child))
                {
                    child = new MeshTreeFolderNode { Name = folder };
                    node.Children.Add(folder, child);
                }

                node = child;
            }

            node.Meshes.Add(mesh);
        }

        foreach (var folder in root.Children.Values.OrderBy(static f => f.Name, StringComparer.OrdinalIgnoreCase))
        {
            AddCompressedFolderNode(MeshTree.Items, folder);
        }
    }

    private void AddCompressedFolderNode(ItemCollection target, MeshTreeFolderNode source)
    {
        var displayName = source.Name;
        var node = source;

        // VS Code-like compact folders: collapse single-child folder chains.
        while (node.Meshes.Count == 0 && node.Children.Count == 1)
        {
            var next = node.Children.Values.First();
            displayName += "/" + next.Name;
            node = next;
        }

        var folderItem = new TreeViewItem { Header = displayName };
        target.Add(folderItem);

        foreach (var child in node.Children.Values.OrderBy(static c => c.Name, StringComparer.OrdinalIgnoreCase))
        {
            AddCompressedFolderNode(folderItem.Items, child);
        }

        foreach (var mesh in node.Meshes.OrderBy(static m => m.Name, StringComparer.OrdinalIgnoreCase))
        {
            var fileName = Path.GetFileName(mesh.RelativePath);
            folderItem.Items.Add(new TreeViewItem { Header = fileName, Tag = mesh });
        }
    }

    private void MeshSearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        ApplyMeshFilter();
    }

    private void ApplyMeshFilter()
    {
        if (_allMeshes.Count == 0)
        {
            MeshTree.Items.Clear();
            MeshCountText.Text = "No meshes";
            return;
        }

        var query = MeshSearchBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(query))
        {
            PopulateTree(_allMeshes);
            MeshCountText.Text = $"{_allMeshes.Count:N0} meshes";
            return;
        }

        var filtered = _allMeshes.Where(static _ => true)
            .Where(m => m.Name.Contains(query, StringComparison.OrdinalIgnoreCase)
                || m.RelativePath.Contains(query, StringComparison.OrdinalIgnoreCase))
            .ToList();

        PopulateTree(filtered);
        MeshCountText.Text = $"{filtered.Count:N0} of {_allMeshes.Count:N0} meshes";
    }

    private async void MeshTree_SelectedItemChanged(object sender, RoutedPropertyChangedEventArgs<object> e)
    {
        if (e.NewValue is not TreeViewItem { Tag: MeshCatalogEntry mesh })
        {
            return;
        }

        try
        {
            StatusText.Text = $"Loading {mesh.Name}";
            SelectionTitle.Text = mesh.Name;
            SelectionMeta.Text = mesh.RelativePath;
            DetailsBox.Text = $"Loading {mesh.Name}...";

            var meshData = await Task.Run(() => TgcMeshReader.ReadMesh(mesh.FullPath));
            var material = await Task.Run(() => SkyResourceResolver.ResolveMaterial(mesh.FullPath));
            var scale = await Task.Run(() => SkyResourceResolver.ResolveScale(mesh.FullPath));

            RenderMesh(meshData, material, scale ?? 1.0f);

            var skeletonCount = meshData.EmbeddedSkeleton?.Count ?? 0;
            var materialSummary = material is null
                ? "<none>"
                : $"{material.Source} :: {material.Name} | shader={material.Shader} | diff={material.DiffuseTex}";
            StatusText.Text = $"Loaded {meshData.Name}";
            SelectionTitle.Text = meshData.Name;
            SelectionMeta.Text = $"{mesh.Category} | {mesh.RelativePath}";

            DetailsBox.Text = string.Join(Environment.NewLine, new[]
            {
                $"Name: {meshData.Name}",
                $"Path: {meshData.SourcePath}",
                $"Category: {mesh.Category}",
                $"Version: 0x{meshData.Version:X}",
                $"Animated: {meshData.IsAnimated}",
                $"Vertices: {meshData.Vertices.Count}",
                $"Faces: {meshData.Indices.Count}",
                $"UV0: {meshData.Uv0.Count}",
                $"Mesh Packed Vertex Attrs: {meshData.PackedVertexAttrs.Count}",
                $"Weighted Vertices: {meshData.BoneWeights.Count}",
                $"Embedded Skeleton Bones: {skeletonCount}",
                $"Auto Scale (PlaceableDefs): {(scale.HasValue ? scale.Value.ToString("0.###") : "<none>")}",
                $"Material: {materialSummary}",
            });
        }
        catch (Exception ex)
        {
            StatusText.Text = "Load failed";
            DetailsBox.Text = ex.ToString();
        }
    }

    private Model3DGroup CreateEmptyScene()
    {
        var group = new Model3DGroup();
        group.Children.Add(new AmbientLight(Color.FromRgb(118, 118, 118)));

        var keyDir = BuildLightDirection();
        _keyLight = new DirectionalLight(Color.FromRgb(244, 244, 244), keyDir);
        _fillLight = new DirectionalLight(Color.FromRgb(150, 160, 176), new Vector3D(-keyDir.X * 0.35, -0.5, -keyDir.Z * 0.35));
        group.Children.Add(_keyLight);
        group.Children.Add(_fillLight);
        return group;
    }

    private Vector3D BuildLightDirection()
    {
        _lightPitch = Math.Clamp(_lightPitch, -1.3, 1.3);
        var cp = Math.Cos(_lightPitch);
        var dir = new Vector3D(cp * Math.Cos(_lightYaw), Math.Sin(_lightPitch), cp * Math.Sin(_lightYaw));
        if (dir.Length < 1e-6)
        {
            return new Vector3D(-0.6, -0.5, -0.6);
        }

        dir.Normalize();
        return dir;
    }

    private void UpdateSceneLights()
    {
        if (_keyLight is null || _fillLight is null)
        {
            return;
        }

        var keyDir = BuildLightDirection();
        _keyLight.Direction = keyDir;
        _fillLight.Direction = new Vector3D(-keyDir.X * 0.35, -0.5, -keyDir.Z * 0.35);
    }

    private void RenderMesh(MeshData meshData, MaterialInfo? materialInfo, float scale)
    {
        if (meshData.Vertices.Count == 0 || meshData.Indices.Count == 0)
        {
            _currentGeometry = null;
            _currentPositions = null;
            _currentMaterialInfo = null;
            _currentMeshPath = null;
            _currentSkeleton = null;
            SceneVisual.Content = CreateEmptyScene();
            return;
        }

        var geometry = new MeshGeometry3D();
        var positions = new Point3DCollection(meshData.Vertices.Count);
        var uvs = new PointCollection(meshData.Vertices.Count);

        foreach (var (x, y, z) in meshData.Vertices)
        {
            // Face meshes toward camera: 180-degree rotation around vertical axis.
            positions.Add(new Point3D(-x * scale, y * scale, -z * scale));
        }

        for (var i = 0; i < meshData.Vertices.Count; i++)
        {
            if (i < meshData.Uv0.Count)
            {
                var uv = meshData.Uv0[i];
                uvs.Add(new System.Windows.Point(uv.U, uv.V));
            }
            else
            {
                uvs.Add(new System.Windows.Point(0, 0));
            }
        }

        var tri = new Int32Collection(meshData.Indices.Count * 3);
        foreach (var (a, b, c) in meshData.Indices)
        {
            if (a < 0 || b < 0 || c < 0 || a >= positions.Count || b >= positions.Count || c >= positions.Count)
            {
                continue;
            }

            tri.Add(a);
            tri.Add(b);
            tri.Add(c);
        }

        geometry.Positions = positions;
        geometry.TextureCoordinates = uvs;
        geometry.TriangleIndices = tri;

        _currentGeometry = geometry;
        _currentPositions = positions;
        _currentMaterialInfo = materialInfo;
        _currentMeshPath = meshData.SourcePath;
        // Keep skeleton counts in details, but hide bone markers in the viewport for now.
        _currentSkeleton = null;

        RebuildScene();

        FrameCamera(positions);
    }

    private void RebuildScene()
    {
        var group = CreateEmptyScene();
        if (_currentGeometry is not null)
        {
            if (_viewMode == ViewMode.Wire)
            {
                var fill = new GeometryModel3D
                {
                    Geometry = _currentGeometry,
                    Material = new DiffuseMaterial(new SolidColorBrush(Color.FromArgb(28, 120, 135, 155))),
                    BackMaterial = new DiffuseMaterial(new SolidColorBrush(Color.FromArgb(18, 95, 95, 105))),
                };
                group.Children.Add(fill);

                var wire = BuildWireOverlay(_currentGeometry, EstimateWireThickness());
                if (wire is not null)
                {
                    group.Children.Add(wire);
                }
            }
            else
            {
                var material = _viewMode == ViewMode.Texture
                    ? BuildSurfaceMaterial(_currentMeshPath ?? string.Empty, _currentMaterialInfo)
                    : BuildSolidMaterial();
                group.Children.Add(new GeometryModel3D
                {
                    Geometry = _currentGeometry,
                    Material = material,
                    BackMaterial = new DiffuseMaterial(new SolidColorBrush(Color.FromRgb(45, 48, 54))),
                });
            }
        }

        if (_currentSkeleton is not null)
        {
            group.Children.Add(_currentSkeleton);
        }

        SceneVisual.Content = group;
    }

    private Material BuildSurfaceMaterial(string meshPath, MaterialInfo? materialInfo)
    {
        var diffuseName = materialInfo?.DiffuseTex;
        var texturePath = string.IsNullOrWhiteSpace(diffuseName)
            ? null
            : SkyResourceResolver.FindTextureFile(meshPath, diffuseName);

        Brush baseBrush = new SolidColorBrush(Color.FromRgb(214, 222, 232));

        if (!string.IsNullOrWhiteSpace(texturePath))
        {
            var image = TextureLoader.Load(texturePath);
            if (image is not null)
            {
                baseBrush = new ImageBrush(image)
                {
                    Stretch = Stretch.Fill,
                    Opacity = 1.0,
                };
            }
        }

        var group = new MaterialGroup();
        group.Children.Add(new DiffuseMaterial(baseBrush));
        group.Children.Add(new EmissiveMaterial(new SolidColorBrush(Color.FromArgb(20, 255, 255, 255))));
        group.Children.Add(new SpecularMaterial(new SolidColorBrush(Color.FromArgb(90, 255, 255, 255)), 24));
        return group;
    }

    private static Material BuildSolidMaterial()
    {
        var group = new MaterialGroup();
        group.Children.Add(new DiffuseMaterial(new SolidColorBrush(Color.FromRgb(205, 214, 226))));
        group.Children.Add(new EmissiveMaterial(new SolidColorBrush(Color.FromArgb(14, 255, 255, 255))));
        group.Children.Add(new SpecularMaterial(new SolidColorBrush(Color.FromArgb(105, 255, 255, 255)), 18));
        return group;
    }

    private void FrameCamera(Point3DCollection positions)
    {
        if (positions.Count == 0)
        {
            return;
        }

        var minX = positions[0].X;
        var minY = positions[0].Y;
        var minZ = positions[0].Z;
        var maxX = positions[0].X;
        var maxY = positions[0].Y;
        var maxZ = positions[0].Z;

        foreach (var p in positions)
        {
            minX = Math.Min(minX, p.X);
            minY = Math.Min(minY, p.Y);
            minZ = Math.Min(minZ, p.Z);
            maxX = Math.Max(maxX, p.X);
            maxY = Math.Max(maxY, p.Y);
            maxZ = Math.Max(maxZ, p.Z);
        }

        var center = new Point3D((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
        var sizeX = maxX - minX;
        var sizeY = maxY - minY;
        var sizeZ = maxZ - minZ;
        var radius = Math.Max(Math.Max(sizeX, sizeY), sizeZ) * 0.5;
        if (radius < 0.001)
        {
            radius = 0.5;
        }

        _cameraTarget = center;
        _cameraDistance = Math.Max(radius * 3.4, 0.35);
        _cameraYaw = -2.3561944902;
        _cameraPitch = -0.35;
        _camera.FarPlaneDistance = Math.Max(5000, radius * 100);
        UpdateCamera();
    }

    private static GeometryModel3D? BuildWireOverlay(MeshGeometry3D source, double thickness)
    {
        if (source.Positions.Count == 0 || source.TriangleIndices.Count < 3)
        {
            return null;
        }

        var edges = new HashSet<(int A, int B)>();
        for (var i = 0; i + 2 < source.TriangleIndices.Count; i += 3)
        {
            AddEdge(edges, source.TriangleIndices[i], source.TriangleIndices[i + 1]);
            AddEdge(edges, source.TriangleIndices[i + 1], source.TriangleIndices[i + 2]);
            AddEdge(edges, source.TriangleIndices[i + 2], source.TriangleIndices[i]);
        }

        if (edges.Count == 0)
        {
            return null;
        }

        var maxEdges = 120000;
        var overlay = new MeshGeometry3D();
        var count = 0;
        foreach (var edge in edges)
        {
            if (count++ >= maxEdges)
            {
                break;
            }

            var a = source.Positions[edge.A];
            var b = source.Positions[edge.B];
            AddEdgePrism(overlay, a, b, thickness);
        }

        return new GeometryModel3D
        {
            Geometry = overlay,
            Material = new DiffuseMaterial(new SolidColorBrush(Color.FromRgb(250, 250, 250))),
            BackMaterial = new DiffuseMaterial(new SolidColorBrush(Color.FromRgb(210, 210, 210))),
        };
    }

    private static void AddEdge(HashSet<(int A, int B)> edges, int a, int b)
    {
        if (a == b)
        {
            return;
        }

        edges.Add(a < b ? (a, b) : (b, a));
    }

    private static void AddEdgePrism(MeshGeometry3D geometry, Point3D a, Point3D b, double thickness)
    {
        var dir = b - a;
        var len = dir.Length;
        if (len < 1e-6)
        {
            return;
        }

        dir.Normalize();
        var up = Math.Abs(Vector3D.DotProduct(dir, new Vector3D(0, 1, 0))) > 0.95
            ? new Vector3D(1, 0, 0)
            : new Vector3D(0, 1, 0);
        var right = Vector3D.CrossProduct(dir, up);
        if (right.Length < 1e-6)
        {
            return;
        }

        right.Normalize();
        var normal = Vector3D.CrossProduct(dir, right);
        normal.Normalize();
        right *= thickness * 0.5;
        normal *= thickness * 0.5;

        var p0 = a - right - normal;
        var p1 = a + right - normal;
        var p2 = a + right + normal;
        var p3 = a - right + normal;
        var p4 = b - right - normal;
        var p5 = b + right - normal;
        var p6 = b + right + normal;
        var p7 = b - right + normal;

        AddQuad(geometry, p0, p1, p5, p4);
        AddQuad(geometry, p1, p2, p6, p5);
        AddQuad(geometry, p2, p3, p7, p6);
        AddQuad(geometry, p3, p0, p4, p7);
    }

    private static void AddQuad(MeshGeometry3D geometry, Point3D a, Point3D b, Point3D c, Point3D d)
    {
        AddTriangle(geometry, a, b, c);
        AddTriangle(geometry, a, c, d);
    }

    private double EstimateWireThickness()
    {
        if (_currentPositions is null || _currentPositions.Count == 0)
        {
            return 0.01;
        }

        var minX = _currentPositions[0].X;
        var minY = _currentPositions[0].Y;
        var minZ = _currentPositions[0].Z;
        var maxX = minX;
        var maxY = minY;
        var maxZ = minZ;
        foreach (var p in _currentPositions)
        {
            minX = Math.Min(minX, p.X);
            minY = Math.Min(minY, p.Y);
            minZ = Math.Min(minZ, p.Z);
            maxX = Math.Max(maxX, p.X);
            maxY = Math.Max(maxY, p.Y);
            maxZ = Math.Max(maxZ, p.Z);
        }

        var span = Math.Max(Math.Max(maxX - minX, maxY - minY), maxZ - minZ);
        return Math.Max(span * 0.0015, 0.0015);
    }

    private void UpdateCamera()
    {
        _cameraPitch = Math.Clamp(_cameraPitch, -1.45, 1.45);
        _cameraDistance = Math.Clamp(_cameraDistance, 0.05, 500000);

        var cp = Math.Cos(_cameraPitch);
        var forward = new Vector3D(cp * Math.Cos(_cameraYaw), Math.Sin(_cameraPitch), cp * Math.Sin(_cameraYaw));
        forward.Normalize();

        var pos = _cameraTarget - (forward * _cameraDistance);
        _camera.Position = pos;
        _camera.LookDirection = _cameraTarget - pos;
        _camera.UpDirection = new Vector3D(0, 1, 0);
        _camera.FieldOfView = 55;
        _camera.NearPlaneDistance = Math.Max(0.001, _cameraDistance * 0.0005);
        if (_camera.FarPlaneDistance < _cameraDistance * 10)
        {
            _camera.FarPlaneDistance = _cameraDistance * 100;
        }
    }

    private void RenderModeButton_Checked(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton { Tag: string mode })
        {
            return;
        }

        _viewMode = mode switch
        {
            "Solid" => ViewMode.Solid,
            "Wire" => ViewMode.Wire,
            _ => ViewMode.Texture,
        };

        // Only rebuild if we have a loaded mesh
        if (_currentGeometry is not null)
        {
            RebuildScene();
        }
    }

    private void ViewportHost_MouseDown(object sender, MouseButtonEventArgs e)
    {
        MeshViewport.Focus();
        _lastMousePosition = e.GetPosition(MeshViewport);
        if (e.ChangedButton == MouseButton.Left)
        {
            if ((Keyboard.Modifiers & ModifierKeys.Shift) == ModifierKeys.Shift)
            {
                _isLightOrbiting = true;
            }
            else
            {
                _isOrbiting = true;
            }

            Mouse.Capture((IInputElement)sender);
        }
        else if (e.ChangedButton == MouseButton.Middle || e.ChangedButton == MouseButton.Right)
        {
            _isPanning = true;
            Mouse.Capture((IInputElement)sender);
        }
    }

    private void ViewportHost_MouseUp(object sender, MouseButtonEventArgs e)
    {
        _isOrbiting = false;
        _isPanning = false;
        _isLightOrbiting = false;
        Mouse.Capture(null);
    }

    private void ViewportHost_MouseLeave(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (!_isOrbiting && !_isPanning && !_isLightOrbiting)
        {
            return;
        }

        _isOrbiting = false;
        _isPanning = false;
        _isLightOrbiting = false;
        Mouse.Capture(null);
    }

    private void ViewportHost_MouseMove(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (!_isOrbiting && !_isPanning && !_isLightOrbiting)
        {
            return;
        }

        var cur = e.GetPosition(MeshViewport);
        var delta = cur - _lastMousePosition;
        _lastMousePosition = cur;

        if (_isLightOrbiting)
        {
            _lightYaw += delta.X * 0.01;
            _lightPitch -= delta.Y * 0.01;
            UpdateSceneLights();
            return;
        }

        if (_isOrbiting)
        {
            _cameraYaw += delta.X * 0.01;
            _cameraPitch -= delta.Y * 0.01;
            UpdateCamera();
            return;
        }

        if (_isPanning)
        {
            var forward = _camera.LookDirection;
            forward.Normalize();
            var right = Vector3D.CrossProduct(forward, _camera.UpDirection);
            if (right.Length > 1e-6)
            {
                right.Normalize();
            }

            var up = Vector3D.CrossProduct(right, forward);
            if (up.Length > 1e-6)
            {
                up.Normalize();
            }

            var scale = _cameraDistance * 0.002;
            var shift = (-right * delta.X * scale) + (up * delta.Y * scale);
            _cameraTarget += shift;
            UpdateCamera();
        }
    }

    private void ViewportHost_MouseWheel(object sender, MouseWheelEventArgs e)
    {
        var zoom = Math.Exp(-e.Delta * 0.0012);
        _cameraDistance *= zoom;
        UpdateCamera();
    }

    private Model3D? BuildSkeletonMarkers(List<SkeletonBone> bones, float scale)
    {
        var heads = ExtractBoneHeads(bones, scale);
        if (heads.Count == 0)
        {
            return null;
        }

        var markerSize = Math.Max(0.015f, 0.03f * scale);
        var geo = new MeshGeometry3D();

        foreach (var head in heads)
        {
            AddMarkerCube(geo, head, markerSize);
        }

        return new GeometryModel3D
        {
            Geometry = geo,
            Material = new DiffuseMaterial(new SolidColorBrush(Color.FromRgb(255, 170, 60))),
            BackMaterial = new DiffuseMaterial(new SolidColorBrush(Color.FromRgb(255, 120, 20))),
        };
    }

    private static List<Point3D> ExtractBoneHeads(List<SkeletonBone> bones, float scale)
    {
        var result = new List<Point3D>(bones.Count);
        foreach (var bone in bones)
        {
            if (bone.InverseBindMatrix.Length < 16)
            {
                continue;
            }

            var m = new Matrix3D(
                bone.InverseBindMatrix[0], bone.InverseBindMatrix[1], bone.InverseBindMatrix[2], bone.InverseBindMatrix[3],
                bone.InverseBindMatrix[4], bone.InverseBindMatrix[5], bone.InverseBindMatrix[6], bone.InverseBindMatrix[7],
                bone.InverseBindMatrix[8], bone.InverseBindMatrix[9], bone.InverseBindMatrix[10], bone.InverseBindMatrix[11],
                bone.InverseBindMatrix[12], bone.InverseBindMatrix[13], bone.InverseBindMatrix[14], bone.InverseBindMatrix[15]);

            if (!m.HasInverse)
            {
                continue;
            }

            m.Invert();
            // Keep viewer transform consistent with mesh-space conversion.
            var x = -m.OffsetX * scale;
            var y = m.OffsetY * scale;
            var z = -m.OffsetZ * scale;
            result.Add(new Point3D(x, y, z));
        }

        return result;
    }

    private static void AddMarkerCube(MeshGeometry3D geometry, Point3D center, double size)
    {
        var h = size * 0.5;
        var p0 = new Point3D(center.X - h, center.Y - h, center.Z - h);
        var p1 = new Point3D(center.X + h, center.Y - h, center.Z - h);
        var p2 = new Point3D(center.X + h, center.Y + h, center.Z - h);
        var p3 = new Point3D(center.X - h, center.Y + h, center.Z - h);
        var p4 = new Point3D(center.X - h, center.Y - h, center.Z + h);
        var p5 = new Point3D(center.X + h, center.Y - h, center.Z + h);
        var p6 = new Point3D(center.X + h, center.Y + h, center.Z + h);
        var p7 = new Point3D(center.X - h, center.Y + h, center.Z + h);

        AddTriangle(geometry, p0, p2, p1);
        AddTriangle(geometry, p0, p3, p2);

        AddTriangle(geometry, p4, p5, p6);
        AddTriangle(geometry, p4, p6, p7);

        AddTriangle(geometry, p0, p1, p5);
        AddTriangle(geometry, p0, p5, p4);

        AddTriangle(geometry, p1, p2, p6);
        AddTriangle(geometry, p1, p6, p5);

        AddTriangle(geometry, p2, p3, p7);
        AddTriangle(geometry, p2, p7, p6);

        AddTriangle(geometry, p3, p0, p4);
        AddTriangle(geometry, p3, p4, p7);
    }

    private static void AddTriangle(MeshGeometry3D geometry, Point3D a, Point3D b, Point3D c)
    {
        var baseIndex = geometry.Positions.Count;
        geometry.Positions.Add(a);
        geometry.Positions.Add(b);
        geometry.Positions.Add(c);
        geometry.TriangleIndices.Add(baseIndex);
        geometry.TriangleIndices.Add(baseIndex + 1);
        geometry.TriangleIndices.Add(baseIndex + 2);
    }
}
