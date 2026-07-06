//
//  CardGuideOverlayView.swift
//  WeScan
//

import UIKit

/// Dimmed overlay with an ID-card shaped cutout drawn over the scanner preview
final class CardGuideOverlayView: UIView {

    private let dimLayer = CAShapeLayer()
    private let borderLayer = CAShapeLayer()

    private(set) var guideRect: CGRect = .zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false

        dimLayer.fillRule = .evenOdd
        dimLayer.fillColor = UIColor.black.withAlphaComponent(0.55).cgColor
        borderLayer.fillColor = UIColor.clear.cgColor
        borderLayer.strokeColor = UIColor.white.cgColor
        borderLayer.lineWidth = 3.0
        layer.addSublayer(dimLayer)
        layer.addSublayer(borderLayer)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()

        let guideWidth = bounds.width * 0.85
        let guideHeight = guideWidth / CardGuide.aspectRatio
        guideRect = CGRect(
            x: (bounds.width - guideWidth) / 2.0,
            y: bounds.height * 0.42 - guideHeight / 2.0,
            width: guideWidth,
            height: guideHeight
        )

        let cutout = UIBezierPath(roundedRect: guideRect, cornerRadius: 12.0)
        let dimPath = UIBezierPath(rect: bounds)
        dimPath.append(cutout)
        dimLayer.path = dimPath.cgPath
        borderLayer.path = cutout.cgPath
    }

    func setDetected(_ detected: Bool) {
        borderLayer.strokeColor = (detected ? UIColor.systemGreen : UIColor.white).cgColor
    }

    /// Whether the given quad (view coordinates) fits inside the guide with enough coverage
    func contains(quad: Quadrilateral) -> Bool {
        guard guideRect != .zero else { return false }

        let tolerance = guideRect.width * 0.06
        let expanded = guideRect.insetBy(dx: -tolerance, dy: -tolerance)
        let points = [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft]
        guard points.allSatisfy({ expanded.contains($0) }) else { return false }

        // Shoelace formula — reject quads too small relative to the guide
        var area: CGFloat = 0.0
        for (index, point) in points.enumerated() {
            let next = points[(index + 1) % points.count]
            area += point.x * next.y - next.x * point.y
        }
        return abs(area) / 2.0 >= guideRect.width * guideRect.height * 0.5
    }
}
